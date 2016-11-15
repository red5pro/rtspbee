package com.infrared5.rtspbee;

public interface ClientHandler {

	public void playbackBegin(RTSPBullet rtspCameraClient);

	public void playbackEnd(RTSPBullet rtspCameraClient);

	public void unknownHostError(RTSPBullet rtspCameraClient);

	public void streamError(RTSPBullet rtspCameraClient);

}
