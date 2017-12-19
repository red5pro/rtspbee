package com.infrared5.rtspbee;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.util.Base64;
import org.red5.server.api.event.IEvent;
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.net.rtmp.message.Header;

import org.red5.server.stream.ClientBroadcastStream;

import com.red5pro.server.stream.sdp.SDPTrack;
import com.red5pro.server.stream.sdp.SessionDescription;
import com.red5pro.server.stream.sdp.SessionDescriptionProtocolDecoder;
/**
 * Transcode rtsp over tcp .
 *
 * @author Andy Shaules
 */
public class RTSPBullet implements Runnable {

  private final int order;
  public final String description;
  private int timeout = 10; // seconds
  
  private IBulletCompleteHandler completeHandler;
  private IBulletFailureHandler failHandler;
  public AtomicBoolean completed = new AtomicBoolean(false);
  volatile boolean connectionException;
  private Future<?> future;

  private String host = "0.0.0.0";
  private int port = 8554;
  private String contextPath = "";
  private String streamName = "stream1";
  private Socket requestSocket;
  private OutputStream out;
  private long seq = 0;
  private int state = 0;
  private int bodyCounter = 0;
  private Map<String, String> headers = new HashMap<String, String>();
  private volatile boolean doRun = true;
  private boolean fistKey = false;
  private SessionDescriptionProtocolDecoder decoder;
  private SessionDescription sdp;
  @SuppressWarnings("unused")
  private SDPTrack videoTrack;
  private String session;
  @SuppressWarnings("unused")
  private long videoStart;
  
  private boolean hasVideo;
  private boolean hasAudio;
  private ClientBroadcastStream broadcast;

  private String formUri() {
    return "rtsp://" + host + ":" + port + "/" + contextPath + "/" + streamName;
  }

  protected void setupVideo() throws IOException {
	  
		String controlUri = "";
		for (SDPTrack t : sdp.tracks) {
			if(t.announcement.content.toLowerCase().equals("video")){
				hasVideo = true;
				Iterator<Entry<String, String>> iter = t.announcement.attributes.entrySet().iterator();

				while(iter.hasNext()){
					Entry<String, String> m = iter.next();
					if(m.getKey().equals("control")){
						controlUri=m.getValue();
					}
				}
			}
		}
		if(!hasVideo){
			return;
		}
		if(controlUri.toLowerCase().startsWith("rtsp://")){
			out.write(("SETUP " + controlUri+ " RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n"
					+ "User-Agent: Red5Pro\r\n" + "Blocksize : 4096\r\n"
					+ "Transport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n").getBytes());
		}else{
			out.write(("SETUP " + formUri()  + "/" + controlUri+ " RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n"
				+ "User-Agent: Red5Pro\r\n" + "Blocksize : 4096\r\n"
				+ "Transport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n").getBytes());
		}
		out.flush();

		int k = 0;
		String lines = "";

		while ((k = requestSocket.getInputStream().read()) != -1) {

			lines += String.valueOf((char) k);
			if (lines.indexOf("\n") > -1) {

				lines = lines.trim();
				parseHeader(lines);

				if (lines.length() == 0) {
					break;
				}
				lines = "";

			}

		}

    out.write(("SETUP " + formUri() + "/video RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n"
        + "User-Agent: Red5Pro\r\n" + "Blocksize : 4096\r\n"
        + "Transport: RTP/AVP/TCP;interleaved=2-3\r\n\r\n").getBytes());
    out.flush();

  }

  protected void setupAudio() throws IOException {
		String controlUri = "";
		for (SDPTrack t : sdp.tracks) {
			if(t.announcement.content.toLowerCase().equals("audio")){
				hasAudio = true;
				Iterator<Entry<String, String>> iter = t.announcement.attributes.entrySet().iterator();

				while(iter.hasNext()){
					Entry<String, String> m = iter.next();
					if(m.getKey().equals("control")){
						controlUri=m.getValue();
					}
				}
			}
		}
		
		if(!hasAudio){
			return;
		}
		if(controlUri.toLowerCase().startsWith("rtsp://")){
			out.write(("SETUP " + controlUri+" RTSP/1.0\r\nSession:" + session + "\r\nCSeq: " + nextSeq()
			+ "\r\n" + "User-Agent: Red5Pro\r\n" + "Transport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n").getBytes());
		}else{
			out.write(("SETUP " + formUri()  + "/" +  controlUri+" RTSP/1.0\r\nSession:" + session + "\r\nCSeq: " + nextSeq()
			+ "\r\n" + "User-Agent: Red5Pro\r\n" + "Transport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n").getBytes());
		}

		out.flush();
		int k = 0;
		String lines = "";

		while ((k = requestSocket.getInputStream().read()) != -1) {

			lines += String.valueOf((char) k);
			if (lines.indexOf("\n") > -1) {

				lines = lines.trim();
				parseHeader(lines);

				if (lines.length() == 0) {
					break;
				}
				lines = "";

			}

		}
  }

  private synchronized void safeClose(){
    try{
    	stop();
    	if(requestSocket!=null) {
    		System.out.println("Bullet #" + this.order + ", closing socket...");
    		requestSocket.close();
    		requestSocket=null;
    	}
    }catch(Exception e){
    	System.out.println("Error in SAFE CLOSE");
    	e.printStackTrace();
    }
  }

  @SuppressWarnings("unused")
  public void run() {

    boolean mustEnd =false;
    try {

      System.out.println("Bullet #" + this.order + ", Attempting connect to " + host + " in port " + String.valueOf(port));

      requestSocket = new Socket(host, port);

      System.out.println("Bullet #" + this.order + ", Connected to " + host + " in port " + String.valueOf(port));

      out = requestSocket.getOutputStream();
      out.write(("OPTIONS " + formUri() + " RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n"
          + "User-Agent: Red5Pro\r\n\r\n").getBytes());
      out.flush();
      try{
//        System.out.println("parsing options...");
        parseOptions();
      }
      catch(Exception e) {
         safeClose();
         System.out.println("Parsing options error.");
         System.out.println(e.getMessage());
         e.printStackTrace();
         dostreamError();
         return;
      }
      out.write(("DESCRIBE " + formUri() + " RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n"
          + "User-Agent: Red5Pro\r\n" + "Accept: application/sdp\r\n" + "\r\n").getBytes());
      out.flush();
      try{
//        System.out.println("parsing description...");
        parseDescription();
        setupAudio();
        setupVideo();
      }
      catch(Exception e){
        System.out.println("parsing description error...");
        System.out.println(e.getMessage());
        e.printStackTrace();
        safeClose();
        dostreamError();
        return;
      }

//      System.out.println("LETS PLAY...");
      out.write(("PLAY " + formUri() + " RTSP/1.0\r\nCSeq:" + nextSeq() + "\r\n"
          + "User-Agent:Lavf\r\n\r\n").getBytes());
      out.flush();

      int k = 0;
      String lines = "";
      try{
//        System.out.println("reading socket input...");
        
        while ((k = requestSocket.getInputStream().read()) != -1) {
          lines += String.valueOf((char) k);
          if (lines.indexOf("\n") > -1) {
            lines = lines.trim();
            //System.out.println(lines);
            if (lines.length() == 0) {
//              System.out.println("End of header, begin stream.");
              break;
            }
            lines = "";
          }
        }
      }
      catch(Exception e) {
        System.out.println("reading socket input error...");
        System.out.println(e.getMessage());
        e.printStackTrace();
        safeClose();
        dostreamError();
        return;
      }
      
      Red5Bee.submit(new Runnable() {
          public void run() {
              System.out.printf("Successful subscription of bullet, disposing: bullet #%d\n", order);
              if (completed.compareAndSet(false, true)) {
              	if (completeHandler != null) {
              		completeHandler.OnBulletComplete();
                }
              }
          }
      }, timeout, TimeUnit.SECONDS);

      mustEnd = true;
      int lengthToRead = 0;// incoming packet length.
      while (doRun && (k = requestSocket.getInputStream().read()) != -1) {
        //System.out.println("doRun run.");
        //isPlaying=true;
			if (k == 36) {

				int buffer[] = new int[3];
				buffer[0] = requestSocket.getInputStream().read() & 0xff;
				buffer[1] = requestSocket.getInputStream().read() & 0xff;
				buffer[2] = requestSocket.getInputStream().read() & 0xff;

				lengthToRead = (buffer[1] << 8 | buffer[2]);

				byte packet[] = new byte[lengthToRead];// copy packet.

				k = 0;
				while (lengthToRead-- > 0) {
					packet[k++] = (byte) requestSocket.getInputStream().read();
				}
				// packet header info.
				int cursor = 0;
				int version = (packet[0] >> 6) & 0x3;// rtp
				int p = (packet[0] >> 5) & 0x1;
				int x = (packet[0] >> 4) & 0x1;
				int cc = packet[0] & 0xf;// count of sync srsc
				int m = packet[1] >> 7;// m bit
				int pt = packet[1] & (0xff >> 1);// packet type
				int sn = packet[2] << 8 | packet[3];// sequence number.
				long time = ((packet[4] & 0xFF) << 24 | (packet[5] & 0xFF) << 16 | (packet[6] & 0xFF) << 8
						| (packet[7] & 0xFF)) & 0xFFFFFFFFL;

				cursor = 12;
				// parse any other clock sources.
				for (int y = 0; (y < cc); y++) {
					long CSRC = packet[cursor++] << 24 | packet[cursor++] << 16 | packet[cursor++] << 8
							| packet[cursor++];
				}
				// based on packet type.
				int type = 0;
//				System.out.println("read packet");
				
			}
		}

      System.out.println("--- teardown ---");
      
      out.write(("TEARDOWN " + formUri() + " RTSP/1.0\r\n" + "CSeq: " + nextSeq() + "\r\n"
          + "User-Agent: Red5-Pro\r\n" + "Session: " + session + " \r\n\r\n").getBytes());

      out.flush();
      out.close();
      
      System.out.println("---/ teardown ---");
      
    } catch (UnknownHostException unknownHost) {
      unknownHost.printStackTrace();
      dostreamError();
    } catch (ConnectException conXcept) {
      System.out.println("--- connect error ---");
      System.out.println(conXcept.getMessage());
      conXcept.printStackTrace();
      dostreamError();
      System.out.println("---/ connect error ---");
    } catch (IOException ioException) {
      System.out.println("--- io error ---");
      System.out.println(ioException.getMessage());
      ioException.printStackTrace();
      System.out.println("---/ io error ---");
      dostreamError();
    } catch (Exception genException) {
      System.out.println("--- general error ---");
      System.out.println(genException.getMessage());
      genException.printStackTrace();
      System.out.println("---/ general error ---");
      dostreamError();
    } finally {
      safeClose();
    }
  }
  private void dostreamError() {
	  
	  final IBulletFailureHandler thisFail = this.failHandler;
	  System.out.println("Bullet #" + this.order + ", stream error.");
	  new Thread(new Runnable(){
	
	    @Override
	    public void run() {
	      thisFail.OnBulletFireFail();
	    }}).start();
    
  }

  private void parseOptions() throws IOException {
    int lineLength = Integer.MAX_VALUE;
    int k = 0;
    String returnData = "";
    while (lineLength > 0 && (k = requestSocket.getInputStream().read()) != -1) {
      returnData = returnData + String.valueOf((char) k);
      if (returnData.indexOf("\n") > -1) {

        //System.out.println(returnData.trim());
        lineLength = returnData.trim().length();
        returnData = "";
      }
    }
  }

  private void parseDescription() throws IOException {
    int k = 0;

    String returnData = "";

    while (bodyCounter == 0 && (k = requestSocket.getInputStream().read()) != -1) {
      // get header
      returnData = returnData + String.valueOf((char) k);
      // read it line by line.
      if (state < 3) {
        if (returnData.indexOf("\n") > -1) {
          //System.out.println(returnData);
          parse(returnData);

          returnData = "";
        } else {
          continue;
        }
      } else {
        // got description header.
        break;
      }
    }
    // parse description body.
    int localVal = bodyCounter;
    // get body
    while (localVal-- > 0 && (k = requestSocket.getInputStream().read()) != -1) {

      returnData = returnData + String.valueOf((char) k);
      // read it line by line.
      if (state < 3) {
        if (returnData.indexOf("\n") > -1) {
          parse(returnData);
          returnData = "";
        } else {
          continue;
        }
      } else {
        break;
      }
    }
    sdp = decoder.decode();
    // We have the sdp description data.
//    System.out.println("--- sdp ---");
//    System.out.println(sdp.toString());
//    System.out.println("--- /sdp ---");
    String propsets = "";
    for (SDPTrack t : sdp.tracks) {
      if (t.announcement.content.equals(SessionDescription.VIDEO)) {
//        videoTrack = t;
        propsets = t.parameters.parameters.get("sprop-parameter-sets");
      } else if (t.announcement.content.equals(SessionDescription.AUDIO)) {
//        audioTrack = t;
      }
    }

  }

  private long nextSeq() {
    return ++seq;
  }

  private void parseHeader(String s) {

    String[] hdr = new String[2];
    int idx = s.indexOf(":");
    // end of header?

//    System.out.println("parseHeader: " + s);
//    System.out.println("--- headers ---");
//    System.out.println(headers);
//    System.out.println("--- /headers ---");
    if (idx < 0 && headers.get("Content-Length") != null) {
      bodyCounter = Integer.valueOf(headers.get("Content-Length"));
      state = 2;
      return;
    }
    else if (idx < 0) {
      return;
    }
    hdr[0] = s.substring(0, idx);
    hdr[1] = s.substring(idx + 1);
    hdr[1] = hdr[1].replace("\r", "");
    hdr[1] = hdr[1].replace("\n", "");
    if (hdr[0].trim().equals("Session")) {
      this.session = hdr[1].trim();
    }
    headers.put(hdr[0].trim(), hdr[1].trim());
//    System.out.println("--- hdr ---");
//    System.out.println(hdr[0] + " = " + hdr[1]);
//    System.out.println("--- /hdr ---");
//    System.out.println("--- session ---");
//    System.out.println(this.session);
//    System.out.println("--- /session ---");
  }

  private void parse(String s) {
    if (state == 1) {// RTSP OK 200, get headers.
//      System.out.println("Parse Headers..." + s);
      parseHeader(s);
      return;
    }
    if (state == 2) {// DESCRIBE results.
//      System.out.println("Parse Describe.... " + s);
      parseDescribeBody(s);
      return;
    }
    if (state == 3) {// Done. Ready for setup/playback.
      return;
    }
    // check for RTSP OK 200
    String[] tokens = s.split(" ");
    for (int i = 0; i < tokens.length; i++) {
      if ((tokens[i].indexOf("200") > -1)) {
        state = 1;
      }
    }
  }

  private void parseDescribeBody(String s) {

//    System.out.println("parseDescriptionBody: " + s);

    if (decoder == null) {
//      System.out.println("Lets create a new decoder...");
      this.decoder = new SessionDescriptionProtocolDecoder();
    }

//    System.out.println("gonna do a check...");
//    System.out.println("Index? " + s.indexOf("="));
    int idx = s.indexOf("=");
//    System.out.println("= index: " + idx);
    if (idx < 0) {// finished with body header?
      state = 3;
      return;
    }

//    System.out.println("Decoder read.");
    decoder.readLine(s);
  }


  public void stop() {
    doRun = false;
  }

  public boolean isRunnning() {
    return doRun;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getContextPath() {
    return contextPath;
  }

  public void setContextPath(String contextPath) {
    this.contextPath = contextPath;
  }

  public String getStreamName() {
    return streamName;
  }

  public void setStreamName(String streamName) {
    this.streamName = streamName;
  }

  public void setCompleteHandler(IBulletCompleteHandler completeHandler) {
      this.completeHandler = completeHandler;
  }

  public void setFailHandler(IBulletFailureHandler failHandler) {
      this.failHandler = failHandler;
  }


	/**
	 * Constructs a bullet which represents an RTMPClient.
	 *
	 * @param url
	 * @param port
	 * @param application
	 * @param streamName
	 */
	private RTSPBullet(int order, String url, int port, String application, String streamName) {
	    this.order = order;
	    this.host = url;
	    this.port = port;
	    this.contextPath = application;
	    this.streamName = streamName;
	    this.description = toString();
	}
	
	/**
	 * Constructs a bullet which represents an RTMPClient.
	 *
	 * @param url
	 * @param port
	 * @param application
	 * @param streamName
	 * @param timeout
	 */
	private RTSPBullet(int order, String url, int port, String application, String streamName, int timeout) {
	    this(order, url, port, application, streamName);
	    this.timeout = timeout;
	}
	
	public String toString() {
	    return StringUtils.join(new String[] { "(bullet #" + this.order + ")", "URL: " + this.host, "PORT: " + this.port, "APP: " + this.contextPath, "NAME: " + this.streamName }, "\n");
	}

	static final class Builder {

        static RTSPBullet build(int order, String url, int port, String application, String streamName) {
            return new RTSPBullet(order, url, port, application, streamName);
        }

        static RTSPBullet build(int order, String url, int port, String application, String streamName, int timeout) {
            return new RTSPBullet(order, url, port, application, streamName, timeout);
        }

    }

}
