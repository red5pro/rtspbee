# rtspbee
RTSPBee for load testing.

# Build

```sh
$ mvn clean compile assembly:single
```

# Run

1. Start a stream on `yourserverURL` with stream name: `mystream`:
2. Issue the following, after building:

```sh
$ cd target
$ java -jar -noverify rtspbee-1.0.0-jar-with-dependencies.jar yourserverURL 8554 live mystream 1 10
```

The above will run a single bee sting lasting 10 seconds.

# Run with Stream Manager support

> Note: The easiest way to start a publisher using the Stream Manager API is to use the *Publish - Stream Manager Proxy* test from the _webrtcexamples_ available in the Red5 Pro Server distribution.

1. Start a stream through the Stream Manager at `yourstreammanagerURL` with stream name `mystream`:
2. Issue the following, after building:

```sh
$ cd target
$ java -jar -noverify rtspbee-1.0.0-jar-with-dependencies.jar "http://yourstreammanagerURL:5080/streammanager/api/2.0/event/live/todd?action=subscribe" 8554 1 10
```

The above will run a single bee sting lasting 10 seconds.

# Notes

## Stream Manager API

For the Stream Manager example, it is important to note that the insecure IP address is required. If you are serving your Stream Manager over SSL, the RTSP bee cannot properly use its API due to security restrictions. _It is possible to resolve these security issues in the future, if you download and store the cert in your Java install, [https://stackoverflow.com/questions/21076179/pkix-path-building-failed-and-unable-to-find-valid-certification-path-to-requ](https://stackoverflow.com/questions/21076179/pkix-path-building-failed-and-unable-to-find-valid-certification-path-to-requ)._

## noverify

The above `run` examples use the `-noverify` option when running the bee. Without this option, verification errors are thrown due to the compilation and obsfucation of the Red5 Pro dependency libs.
