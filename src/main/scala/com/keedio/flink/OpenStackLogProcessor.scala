package com.keedio.flink

import java.sql.Timestamp
import java.util
import java.util.concurrent.TimeUnit

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.Cluster.Builder
import com.datastax.driver.core.exceptions.DriverException
import com.keedio.flink.cep.alerts.Alert
import com.keedio.flink.cep.patterns.ErrorCreateVMPattern
import com.keedio.flink.cep.{IAlert, IPattern}
import com.keedio.flink.config.FlinkProperties
import com.keedio.flink.entities.LogEntry
import com.keedio.flink.mappers._
import com.keedio.flink.utils._
import org.apache.flink.api.common.restartstrategy.RestartStrategies
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.tuple._
import org.apache.flink.cep.PatternSelectFunction
import org.apache.flink.cep.scala.{CEP, PatternStream}
import org.apache.flink.core.fs.FileSystem
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala.{createTypeInformation, _}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.{CheckpointingMode, TimeCharacteristic}
import org.apache.flink.streaming.connectors.cassandra.{CassandraSink, ClusterBuilder}
import org.apache.flink.streaming.connectors.kafka._
import org.apache.flink.streaming.util.serialization._
import org.apache.log4j.Logger

import scala.collection.JavaConverters._
import scala.collection.Map

/**
  * Created by luislazaro on 8/2/17.
  * lalazaro@keedio.com
  * Keedio
  */
class OpenStackLogProcessor

object OpenStackLogProcessor {
  val LOG: Logger = Logger.getLogger(classOf[OpenStackLogProcessor])

  def main(args: Array[String]): Unit = {
    lazy val flinkProperties = new FlinkProperties(args)
    lazy val properties: flinkProperties.FlinkProperties.type = flinkProperties.FlinkProperties

    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.enableCheckpointing(properties.CHECKPOINT_INTERVAL)
    env.getCheckpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)
    env.setRestartStrategy(RestartStrategies.fixedDelayRestart(properties.RESTART_ATTEMPTS,
      org.apache.flink.api.common.time.Time.of(properties.RESTART_DELAY, TimeUnit.MINUTES)
    ))
    // Use the Measurement Timestamp of the Event (set a notion of time)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    //source of data is Kafka. We subscribe as consumer via connector FlinkKafkaConsumer08
    val kafkaConsumer: FlinkKafkaConsumer08[String] = new FlinkKafkaConsumer08[String](
      properties.SOURCE_TOPIC, new SimpleStringSchema(), properties.parameterTool.getProperties)

    val stream: DataStream[String] = env.addSource(kafkaConsumer).rebalance

    //parse jsones as logentries
    val streamOfLogs: DataStream[LogEntry] = stream
      .map(s => LogEntry(s, properties.PARSEBODY)).name("map: toLogEntry" + "\n")
      .filter(logEntry => logEntry.isValid()).name("filter: validity" + "\n")
      .filter(logEntry => SyslogCode.acceptedLogLevels.contains(SyslogCode(logEntry.severity))).name("filter: severity" + "\n")
      .rebalance

    streamOfLogs.rebalance.writeAsText("file:///var/tmp/streamOfLogs.txt", FileSystem.WriteMode.OVERWRITE)
      .setParallelism(1).name("writeAsText: file:///var/tmp/streamOfLogs.txt" + "\n")

    //SINKING to Cassandra
    isCassandraSinkEnbled(properties.CASSANDRAHOST, properties.CASSANDRAPORT) match {
      case true => {
        //will populate tables basis on column id : 1h, 6h, ...
        val listOfKeys: Map[String, Int] = Map("1h" -> 3600, "6h" -> 21600, "12h" -> 43200, "24h" -> 86400, "1w" ->
          604800, "1m" -> 2419200)

        //Create a stream of data for each id and map that stream to a specific flink.tuple.
        val listNodeCounter: Map[DataStream[Tuple5[String, String, String, String, String]], Int] = listOfKeys
          .map(e => (logEntryToTupleNC(streamOfLogs.rebalance, e._1, "az1", "boston"), e._2))

        val listServiceCounter: Map[DataStream[Tuple5[String, String, String, String, String]], Int] = listOfKeys
          .map(e => (logEntryToTupleSC(streamOfLogs.rebalance, e._1, "az1", "boston"), e._2))

        val listStackService: Iterable[DataStream[Tuple7[String, String, String, String, Int, String, Int]]] = listOfKeys
          .map(e => logEntryToTupleSS(streamOfLogs.rebalance, e._1, e._2, "boston"))

        val rawLog: DataStream[Tuple7[String, String, String, String, String, Timestamp, String]] =
          logEntryToTupleRL(streamOfLogs.rebalance.rebalance, "boston")

        //sinking to cassandra
        listNodeCounter.foreach(t => {
          CassandraSink.addSink(t._1.javaStream).setQuery("INSERT INTO redhatpoc" +
            ".counters_nodes (id, loglevel, az, " +
            "region, node_type, ts) VALUES (?, ?, ?, ?, ?, now()) USING TTL " + t._2 + ";")
            .setClusterBuilder(new ClusterBuilder() {
              override def buildCluster(builder: Builder): Cluster = {
                builder
                  .addContactPoint(properties.CASSANDRAHOST)
                  .withPort(properties.CASSANDRAPORT.toInt)
                  .build()
              }
            })
            .build()
            .name("counters_nodes " + t._2)
        })

        listServiceCounter.foreach(t => {
          CassandraSink.addSink(t._1.javaStream).setQuery(
            "INSERT INTO redhatpoc.counters_services (id, loglevel, az, region, service, ts) VALUES (?, " +
              "?," +
              " " +
              "?," +
              " " +
              "?, " +
              "?, now()) USING TTL " + t._2 + ";")
            .setClusterBuilder(new ClusterBuilder() {
              override def buildCluster(builder: Builder): Cluster = {
                builder
                  .addContactPoint(properties.CASSANDRAHOST)
                  .withPort(properties.CASSANDRAPORT.toInt)
                  .build()
              }
            })
            .build()
            .name("counters_services " + t._2)
        })

        /**
          * COLS:   |  id    |service|  loglevel|  region| ts   | tfhours   | timeframe| TTL(hiden)
          * VALUES: |  ?     | ?     |    ?     |    ?   | now()|    ?      |    ?     |    ?
          * TUPLE:  | timekey|service|  loglevel|  region|      | timestamp | timeframe|    ttl
          */
        listStackService.foreach(t => {
          CassandraSink.addSink(t.javaStream).setQuery("INSERT INTO redhatpoc.stack_services (id, region, loglevel, " +
            "service, ts, " +
            "timeframe, " + "tfHours)" + " " + "VALUES " + "(?,?,?,?, now(),?,?) USING TTL " + "?" + ";")
            .setClusterBuilder(new ClusterBuilder() {
              override def buildCluster(builder: Builder): Cluster = {
                builder
                  .addContactPoint(properties.CASSANDRAHOST)
                  .withPort(properties.CASSANDRAPORT.toInt)
                  .build()
              }
            })
            .build()
            .name("stack_services")
        })

        CassandraSink.addSink(rawLog.javaStream).setQuery(
          "INSERT INTO redhatpoc.raw_logs (date, region, loglevel, service, node_type, log_ts, payload) " +
            "VALUES " + "(?, ?, ?, ?, ?, ?, ?);")
          .setClusterBuilder(new ClusterBuilder() {
            override def buildCluster(builder: Builder): Cluster = {
              builder
                .addContactPoint(properties.CASSANDRAHOST)
                .withPort(properties.CASSANDRAPORT.toInt)
                .build()
            }
          })
          .build()
          .name("raw_logs")
      }
      case false => LOG.info(s"Sinking to Cassandra DB is disabled.")
    }

    properties.ENABLE_CEP match {
      case false => LOG.info(s"CEP is disabled")
      case true => {
        //assign and emit watermarks: events may arrive unordered
        val streamOfLogsTimestamped: DataStream[LogEntry] = streamOfLogs
          .assignTimestampsAndWatermarks(
            new BoundedOutOfOrdernessTimestampExtractor[LogEntry](Time.seconds(properties.MAXOUTOFORDENESS)) {
              override def extractTimestamp(t: LogEntry): Long = ProcessorHelper.toTimestamp(t.timestamp).getTime
            })
          .setParallelism(1)
        //CEP
        val streamOfErrorAlerts: DataStream[Alert] = toAlertStream(streamOfLogsTimestamped, new ErrorCreateVMPattern).name("toAlertStream")
        val streamErrorString: DataStream[String] = streamOfErrorAlerts.rebalance.map(errorAlert => errorAlert.toString).name("map: ErrorAlertToString").disableChaining()
        val myProducer = new FlinkKafkaProducer08[String](properties.BROKER, properties.TARGET_TOPIC, new SimpleStringSchema())
        // the following is necessary for at-least-once delivery guarantee
        myProducer.setLogFailuresOnly(false) // "false" by default
        myProducer.setFlushOnCheckpoint(false) // "false" by default
        //sinking to kafka
        streamErrorString.addSink(myProducer).name("toKafkaProducer")
      }
    }


    //properties of job client
    val propertiesNames = properties.parameterTool.getProperties.propertyNames().asScala.toSeq
    val propertiesList: Seq[String] = propertiesNames.map(key => s" ${key}  : " + properties.parameterTool.getProperties.getProperty(key.toString))

    try {
      env.execute(s"OpensStack Log Processor - " + propertiesList.mkString("  ;  "))
    } catch {
      case e: DriverException => LOG.error("", e)
    }
  }

  /**
    * Function to map from DataStream of LogEntry to Tuple of node counters
    *
    * @param streamOfLogs
    * @param timeKey
    * @param az
    * @param region
    * @return
    */
  def logEntryToTupleNC(
                         streamOfLogs: DataStream[LogEntry], timeKey: String, az: String,
                         region: String): DataStream[Tuple5[String, String, String, String, String]] = {
    streamOfLogs.map(new RichMapFunctionNC(timeKey, az, region)).name("map: RichMapFunctionNC " + timeKey.toString + "\n")
  }

  /**
    * Function to map from DataStream of LogEntry to Tupe of service counters
    *
    * @param streamOfLogs
    * @param timeKey
    * @param az
    * @param region
    * @return
    */
  def logEntryToTupleSC(streamOfLogs: DataStream[LogEntry], timeKey: String, az: String, region: String):
  DataStream[Tuple5[String, String, String, String, String]] = {
    streamOfLogs.map(new RichMapFunctionSC(timeKey, az, region))name("map: RichMapFunctionSC " + timeKey.toString + "\n")
  }

  /**
    * function to map from Datastream of Logentry to Tuple raw logs
    *
    * @param streamOfLogs
    * @param region
    * @return
    */
  def logEntryToTupleRL(streamOfLogs: DataStream[LogEntry], region: String): DataStream[Tuple7[String, String,
    String, String, String, Timestamp, String]] = {
    streamOfLogs.map(new RichMapFunctionRL(region)).name("map: RichMapFunctionRL " + "\n")
  }

  /**
    * Create a Tuple for stack_services from LogEntry
    *
    * @param streamOfLogs
    * @param timeKey
    * @param valKey
    * @param region
    * @return
    */
  def logEntryToTupleSS(streamOfLogs: DataStream[LogEntry], timeKey: String, valKey: Int, region: String):
  DataStream[Tuple7[String, String, String, String, Int, String, Int]] = {
    streamOfLogs
      .filter(logEntry => ProcessorHelper.isValidPeriodTime(logEntry.timestamp, valKey)).name("filter: validPeriodTime" + "\n")
      .map(new RichMapFunctionSS(timeKey, valKey, region)).name("map: RichMapFunctionSS " + timeKey.toString + "\n")
      .filter(t => t.f6 > 0).name("filter: ttl greater than zero" + "\n")
  }

  /**
    * Generate DataSteam of Alerts
    *
    * @param streamOfLogsTimestamped
    * @param alertPattern
    * @param typeInfo
    * @tparam T
    * @return
    */
  def toAlertStream[T <: IAlert](streamOfLogsTimestamped: DataStream[LogEntry], alertPattern: IPattern[LogEntry, T])
                                (implicit typeInfo: TypeInformation[T]): DataStream[T] = {
    val tempPatternStream: PatternStream[LogEntry] = CEP.pattern(streamOfLogsTimestamped,
      alertPattern.getEventPattern())
    val alerts: DataStream[T] = tempPatternStream.select(new PatternSelectFunction[LogEntry, T] {
      override def select(pattern: util.Map[String, util.List[LogEntry]]): T = alertPattern.createAlert(pattern)
    })
    alerts
  }


  def isCassandraSinkEnbled(cassandraHost: String, cassandraPort: String): Boolean = {
    cassandraHost != "disabled" && cassandraPort != "disabled"
  }


}



