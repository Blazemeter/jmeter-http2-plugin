package com.blazemeter.jmeter.http2.core;

public interface HTTP2StateListener {

  void onConnectionEnds();

  void onLatencyEnds();

}
