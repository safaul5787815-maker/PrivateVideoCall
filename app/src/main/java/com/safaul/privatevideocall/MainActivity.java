package com.safaul.privatevideocall;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;

public class MainActivity extends ComponentActivity {

    private static final int PERMISSION_REQUEST = 100;

    private SurfaceViewRenderer localView;

    private PeerConnectionFactory peerConnectionFactory;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private EglBase eglBase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        localView = findViewById(R.id.localView);

        requestCallPermissions();
    }

    private void requestCallPermissions() {

        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
        };

        boolean cameraGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED;

        boolean microphoneGranted =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED;

        if (!cameraGranted || !microphoneGranted) {

            ActivityCompat.requestPermissions(
                    this,
                    permissions,
                    PERMISSION_REQUEST
            );

        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == PERMISSION_REQUEST) {

            if (grantResults.length >= 2
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {

                Toast.makeText(
                        this,
                        "Camera & Microphone ready",
                        Toast.LENGTH_SHORT
                ).show();

                startCamera();

            } else {

                Toast.makeText(
                        this,
                        "Camera and microphone permission required",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void startCamera() {

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(this)
                        .createInitializationOptions()
        );

        peerConnectionFactory =
                PeerConnectionFactory
                        .builder()
                        .createPeerConnectionFactory();

        eglBase = EglBase.create();

        localView.init(
                eglBase.getEglBaseContext(),
                null
        );

        localView.setMirror(true);

        CameraEnumerator enumerator =
                new Camera2Enumerator(this);

        String[] deviceNames =
                enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {

            if (enumerator.isFrontFacing(deviceName)) {

                videoCapturer =
                        enumerator.createCapturer(
                                deviceName,
                                null
                        );

                break;
            }
        }

        if (videoCapturer == null) {

            Toast.makeText(
                    this,
                    "Front camera not found",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        surfaceTextureHelper =
                SurfaceTextureHelper.create(
                        "CaptureThread",
                        eglBase.getEglBaseContext()
                );

        videoSource =
                peerConnectionFactory
                        .createVideoSource(false);

        videoCapturer.initialize(
                surfaceTextureHelper,
                this,
                videoSource.getCapturerObserver()
        );

        videoCapturer.startCapture(
                1280,
                720,
                30
        );

org.webrtc.VideoTrack localVideoTrack =
        peerConnectionFactory.createVideoTrack(
                "local_video",
                videoSource
        );

localVideoTrack.addSink(localView);
    }

    @Override
    protected void onDestroy() {

        try {

            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
                videoCapturer = null;
            }

        } catch (Exception ignored) {
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (localView != null) {
            localView.release();
        }

        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }

        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }

        super.onDestroy();
    }
}
