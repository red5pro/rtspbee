# rtspbee
RTSPBee for load testing

# build

```sh
$ mvn clean compile assembly:single
```

# run

1. Start a stream on `yourserverURL` with stream name: `mystream`:
2. Issue the following, after building:

```sh
$ cd target
$ java -jar -noverify rtspbee-1.0.0-jar-with-dependencies.jar yourserverURL 8554 live mystream 1 5
```
