package com.keedio.flink.mappers

import com.keedio.flink.entities.LogEntry
import com.keedio.flink.utils.SyslogCode
import org.apache.flink.api.common.functions.RichMapFunction
import org.apache.flink.api.java.tuple.Tuple5
import org.apache.flink.configuration.Configuration
import org.apache.flink.metrics.Counter

/**
  * Created by luislazaro on 28/3/17.
  * lalazaro@keedio.com
  * Keedio
  */
class RichMapFunctionSC(val timeKey: String, val az: String, val region: String)
extends RichMapFunction[LogEntry, Tuple5[String, String, String, String, String]] {

  var counter: Counter = _

  @throws(classOf[Exception])
  override def map(logEntry: LogEntry): Tuple5[String, String, String, String, String] = {
    counter.inc()
    val logLevel: String = SyslogCode.severity(logEntry.severity)
    val service = logEntry.service
    new Tuple5(timeKey, logLevel, az, region, service)
  }

  override def open(configuration: Configuration) = {
    this.counter = getRuntimeContext
      .getMetricGroup
      .counter("myCounter_service_counter")
  }
}

