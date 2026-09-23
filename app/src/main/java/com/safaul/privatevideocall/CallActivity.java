package com.safaul.privatevideocall;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

public class CallActivity extends ComponentActivity {
private String pairId;
private boolean isCaller;
private FirebaseFirestore firestore;
private ListenerRegistration callListener;
private ListenerRegistration callerCandidatesListener;
private ListenerRegistration calleeCandidatesListener;

private String callId;
private boolean isCallStarted = false;
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;

    private Button muteButton;
    private Button endCallButton;
    private Button switchCameraButton;

    private EglBase eglBase;

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;

    private CameraVideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;

    private VideoSource videoSource;
    private VideoTrack localVideoTrack;

    private AudioSource audioSource;
    private AudioTrack localAudioTrack;

    private boolean isMuted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_call);
android.content.Intent intent = getIntent();

pairId = intent.getStringExtra("pairId");
isCaller = intent.getBooleanExtra("isCaller", false);

        localView = findViewById(R.id.localView);
        remoteView = findViewById(R.id.remoteView);

        muteButton = findViewById(R.id.muteButton);
        endCallButton = findViewById(R.id.endCallButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);

        muteButton.setOnClickListener(v -> toggleMute());

        endCallButton.setOnClickListener(v -> {
            stopCall();
            finish();
        });

        switchCameraButton.setOnClickListener(v -> switchCamera());

        if (hasPermissions()) {
            startWebRTC();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                    },
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    private boolean hasPermissions() {

        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
                &&
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == PERMISSION_REQUEST_CODE) {

            if (hasPermissions()) {
                startWebRTC();
            } else {
                Toast.makeText(
                        this,
                        "Camera aur microphone permission required",
                        Toast.LENGTH_LONG
                ).show();

                finish();
            }
        }
    }

    private void startWebRTC() {

firestore = FirebaseFirestore.getInstance();
callId = pairId;

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(this)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
        );

        eglBase = EglBase.create();

        localView.init(
                eglBase.getEglBaseContext(),
                null
        );

        remoteView.init(
                eglBase.getEglBaseContext(),
                null
        );

        localView.setMirror(true);
        remoteView.setMirror(false);

        PeerConnectionFactory.Options options =
                new PeerConnectionFactory.Options();

        peerConnectionFactory =
                PeerConnectionFactory.builder()
                        .setOptions(options)
                        .createPeerConnectionFactory();

        createAudio();

        createCamera();

createPeerConnection();

if (isCaller) {
    createOffer();
    listenForAnswer();
} else {
    listenForOffer();
}

    }

    private void createAudio() {

        MediaConstraints audioConstraints =
                new MediaConstraints();

        audioSource =
                peerConnectionFactory
                        .createAudioSource(audioConstraints);

        localAudioTrack =
                peerConnectionFactory
                        .createAudioTrack(
                                "local_audio",
                                audioSource
                        );
    }

    private void createCamera() {

        CameraEnumerator enumerator =
                new Camera2Enumerator(this);

        String[] deviceNames =
                enumerator.getDeviceNames();

        String selectedCamera = null;

        for (String name : deviceNames) {

            if (enumerator.isFrontFacing(name)) {
                selectedCamera = name;
                break;
            }
        }

        if (selectedCamera == null && deviceNames.length > 0) {
            selectedCamera = deviceNames[0];
        }

        if (selectedCamera == null) {

            Toast.makeText(
                    this,
                    "Camera not found",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        videoCapturer =
                enumerator.createCapturer(
                        selectedCamera,
                        null
                );

        surfaceTextureHelper =
                SurfaceTextureHelper.create(
                        "CameraThread",
                        eglBase.getEglBaseContext()
                );

        videoSource =
                peerConnectionFactory
                        .createVideoSource(
                                videoCapturer.isScreencast()
                        );

        videoCapturer.initialize(
                surfaceTextureHelper,
                this,
                videoSource.getCapturerObserver()
        );

        localVideoTrack =
                peerConnectionFactory
                        .createVideoTrack(
                                "local_video",
                                videoSource
                        );

        localVideoTrack.addSink(localView);

        try {

            videoCapturer.startCapture(
                    1280,
                    720,
                    30
            );

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Camera start failed",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void createPeerConnection() {

        List<PeerConnection.IceServer> iceServers =
                new ArrayList<>();

        iceServers.add(
                PeerConnection.IceServer
                        .builder(
                                "stun:stun.l.google.com:19302"
                        )
                        .createIceServer()
        );

        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(
                        iceServers
                );

        peerConnection =
                peerConnectionFactory
                        .createPeerConnection(
                                configuration,
                                new PeerConnection.Observer() {

                                    @Override
                                    public void onSignalingChange(
                                            PeerConnection.SignalingState state) {
                                    }

                                    @Override
                                    public void onIceConnectionChange(
                                            PeerConnection.IceConnectionState state) {
                                    }

                                    @Override
                                    public void onIceConnectionReceivingChange(
                                            boolean receiving) {
                                    }

                                    @Override
                                    public void onIceGatheringChange(
                                            PeerConnection.IceGatheringState state) {
                                    }

@Override
public void onIceCandidate(
        org.webrtc.IceCandidate candidate) {

    if (firestore == null || callId == null) {
        return;
    }

    Map<String, Object> data = new HashMap<>();

    data.put("sdpMid", candidate.sdpMid);
    data.put("sdpMLineIndex", candidate.sdpMLineIndex);
    data.put("candidate", candidate.sdp);

    String collection =
            isCaller
                    ? "callerCandidates"
                    : "calleeCandidates";

    firestore
            .collection("calls")
            .document(callId)
            .collection(collection)
            .add(data);
}

                                    @Override
                                    public void onIceCandidatesRemoved(
                                            org.webrtc.IceCandidate[] candidates) {
                                    }

                                    @Override
                                    public void onAddStream(
                                            org.webrtc.MediaStream stream) {
                                    }

                                    @Override
                                    public void onRemoveStream(
                                            org.webrtc.MediaStream stream) {
                                    }

                                    @Override
                                    public void onDataChannel(
                                            org.webrtc.DataChannel dataChannel) {
                                    }

                                    @Override
                                    public void onRenegotiationNeeded() {
                                    }

@Override
public void onAddTrack(
        org.webrtc.RtpReceiver receiver,
        org.webrtc.MediaStream[] mediaStreams) {

    org.webrtc.MediaStreamTrack track =
            receiver.track();

    if (track instanceof VideoTrack) {

        VideoTrack remoteVideoTrack =
                (VideoTrack) track;

        runOnUiThread(() ->
                remoteVideoTrack.addSink(remoteView)
        );
    }
}

                                    @Override
                                    public void onConnectionChange(
                                            PeerConnection.PeerConnectionState state) {
                                    }
                                }
                        );

        if (peerConnection == null) {

            Toast.makeText(
                    this,
                    "WebRTC connection create failed",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        if (localAudioTrack != null) {
            peerConnection.addTrack(localAudioTrack);
        }

        if (localVideoTrack != null) {
            peerConnection.addTrack(localVideoTrack);
        }
listenForRemoteCandidates();
    }

    private void toggleMute() {

        if (localAudioTrack == null) {
            return;
        }

        isMuted = !isMuted;

        localAudioTrack.setEnabled(!isMuted);

        muteButton.setText(
                isMuted ? "Unmute" : "Mute"
        );
    }

    private void switchCamera() {

        if (videoCapturer instanceof CameraVideoCapturer) {

            CameraVideoCapturer camera =
                    (CameraVideoCapturer) videoCapturer;

            camera.switchCamera(null);
        }
    }

private void createOffer() {

    if (peerConnection == null) {
        return;
    }

    MediaConstraints constraints =
            new MediaConstraints();

    peerConnection.createOffer(
            new SdpObserver() {

                @Override
                public void onCreateSuccess(
                        SessionDescription sessionDescription) {

                    peerConnection.setLocalDescription(
                            new SdpObserver() {

                                @Override
                                public void onCreateSuccess(
                                        SessionDescription sdp) {
                                }

                                @Override
                                public void onSetSuccess() {

                                    Map<String, Object> offer =
                                            new HashMap<>();

                                    offer.put(
                                            "type",
                                            "offer"
                                    );

                                    offer.put(
                                            "sdp",
                                            sessionDescription.description
                                    );

                                    firestore
                                            .collection("calls")
                                            .document(callId)
                                            .set(offer);
                                }

                                @Override
                                public void onCreateFailure(
                                        String error) {
                                }

                                @Override
                                public void onSetFailure(
                                        String error) {
                                }
                            },
                            sessionDescription
                    );
                }

                @Override
                public void onSetSuccess() {
                }

                @Override
                public void onCreateFailure(
                        String error) {

                    runOnUiThread(() ->
                            Toast.makeText(
                                    CallActivity.this,
                                    "Offer create failed",
                                    Toast.LENGTH_LONG
                            ).show()
                    );
                }

                @Override
                public void onSetFailure(
                        String error) {
                }
            },
            constraints
    );
}

private void createAnswer() {

    if (peerConnection == null) {
        return;
    }

    MediaConstraints constraints =
            new MediaConstraints();

    peerConnection.createAnswer(
            new SdpObserver() {

                @Override
                public void onCreateSuccess(
                        SessionDescription answer) {

                    peerConnection.setLocalDescription(
                            new SdpObserver() {

                                @Override
                                public void onCreateSuccess(
                                        SessionDescription sdp) {
                                }

                                @Override
                                public void onSetSuccess() {

                                    Map<String, Object> answerData =
                                            new HashMap<>();

                                    answerData.put(
                                            "type",
                                            "answer"
                                    );

                                    answerData.put(
                                            "sdp",
                                            answer.description
                                    );

                                    firestore
                                            .collection("calls")
                                            .document(callId)
                                            .update(answerData);
                                }

                                @Override
                                public void onCreateFailure(
                                        String error) {
                                }

                                @Override
                                public void onSetFailure(
                                        String error) {
                                }
                            },
                            answer
                    );
                }

                @Override
                public void onSetSuccess() {
                }

                @Override
                public void onCreateFailure(
                        String error) {

                    runOnUiThread(() ->
                            Toast.makeText(
                                    CallActivity.this,
                                    "Answer create failed",
                                    Toast.LENGTH_LONG
                            ).show()
                    );
                }

                @Override
                public void onSetFailure(
                        String error) {
                }
            },
            constraints
    );
}

private void listenForAnswer() {

    firestore
            .collection("calls")
            .document(callId)
            .addSnapshotListener((document, error) -> {

                if (!isCaller ||
                        error != null ||
                        document == null ||
                        !document.exists()) {
                    return;
                }

                String answerSdp =
                        document.getString("sdp");

                String type =
                        document.getString("type");

                if (answerSdp == null ||
                        !"answer".equals(type)) {
                    return;
                }

                SessionDescription answer =
                        new SessionDescription(
                                SessionDescription.Type.ANSWER,
                                answerSdp
                        );

                peerConnection.setRemoteDescription(
                        new SdpObserver() {

                            @Override
                            public void onSetSuccess() {
                            }

                            @Override
                            public void onCreateSuccess(
                                    SessionDescription sdp) {
                            }

                            @Override
                            public void onCreateFailure(
                                    String error) {
                            }

                            @Override
                            public void onSetFailure(
                                    String error) {
                            }
                        },
                        answer
                );
            });
}

private void listenForOffer() {

    firestore
            .collection("calls")
            .document(callId)
            .addSnapshotListener((document, error) -> {

                if (error != null ||
                        document == null ||
                        !document.exists()) {
                    return;
                }

                String offerSdp =
                        document.getString("sdp");

                String type =
                        document.getString("type");

                if (offerSdp == null ||
                        !"offer".equals(type)) {
                    return;
                }

                SessionDescription offer =
                        new SessionDescription(
                                SessionDescription.Type.OFFER,
                                offerSdp
                        );

                peerConnection.setRemoteDescription(
                        new SdpObserver() {

                            @Override
                            public void onSetSuccess() {
                                createAnswer();
                            }

                            @Override
                            public void onCreateSuccess(
                                    SessionDescription sdp) {
                            }

                            @Override
                            public void onCreateFailure(
                                    String error) {
                            }

                            @Override
                            public void onSetFailure(
                                    String error) {
                            }
                        },
                        offer
                );
            });
}
private void listenForRemoteCandidates() {

    String collection =
            isCaller
                    ? "calleeCandidates"
                    : "callerCandidates";

    firestore
            .collection("calls")
            .document(callId)
            .collection(collection)
            .addSnapshotListener((snapshots, error) -> {

                if (error != null || snapshots == null) {
                    return;
                }

                for (DocumentSnapshot document :
                        snapshots.getDocuments()) {

                    String candidateSdp =
                            document.getString("candidate");

                    String sdpMid =
                            document.getString("sdpMid");

                    Long sdpMLineIndex =
                            document.getLong("sdpMLineIndex");

                    if (candidateSdp == null ||
                            sdpMid == null ||
                            sdpMLineIndex == null) {
                        continue;
                    }

                    org.webrtc.IceCandidate candidate =
                            new org.webrtc.IceCandidate(
                                    sdpMid,
                                    sdpMLineIndex.intValue(),
                                    candidateSdp
                            );

                    if (peerConnection != null) {
                        peerConnection.addIceCandidate(candidate);
                    }
                }
            });
}
    private void stopCall() {

        try {
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
            }
        } catch (Exception ignored) {
        }

        if (videoCapturer != null) {
            videoCapturer.dispose();
            videoCapturer = null;
        }

        if (localView != null) {
            localView.release();
        }

        if (remoteView != null) {
            remoteView.release();
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }

        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }

        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    @Override
    protected void onDestroy() {

        stopCall();

        super.onDestroy();
    }
}
