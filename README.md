# HTTP2 Plugin for JMeter

![labs-logo](docs/blazemeter-labs-logo.png)

This plugin provides an HTTP2 Sampler.

# Auth Manager
Until the time of writing this, we only support the authentication Basic and Digest mechanism.
To make use of Basic preemptive authentication results, make sure to set the property `httpclient4.auth.preemptive`
to true.