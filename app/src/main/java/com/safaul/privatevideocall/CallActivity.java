package com.safaul.privatevideocall;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CallActivity extends ComponentActivity {

    private String pairId;
    private boolean isCaller;

    private FirebaseFirestore firestore;

    private ListenerRegistration callListener;
    private ListenerRegistration candidateListener;

    private String callId;

    private boolean remoteDescriptionSet = false;
    private boolean offerHandled = false;
    private boolean answerHandled = false;
    private boolean cleanedUp = false;

    private final List<org.webrtc.IceCandidate>
            pendingIceCandidates = new ArrayList<>();

    private final Set<String> receivedCandidateIds =
            new HashSet<>();

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

        pairId = getIntent().getStringExtra("pairId");
        isCaller = getIntent().getBooleanExtra(
                "isCaller",
                false
        );

        if (pairId == null || pairId.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Invalid call",
                    Toast.LENGTH_LONG
            ).show();

            finish();
            return;
        }

        localView = findViewById(R.id.localView);
        remoteView = findViewById(R.id.remoteView);

        muteButton = findViewById(R.id.muteButton);
        endCallButton = findViewById(R.id.endCallButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);


        muteButton.setOnClickListener(v -> toggleMute());


        switchCameraButton.setOnClickListener(
                v -> switchCamera()
        );


        endCallButton.setOnClickListener(v -> {
            stopCall();
            finish();
        });


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
                        "Camera and microphone permission required",
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


        /*
         * Important:
         * Caller must start listening for answer
         * BEFORE creating the offer.
         */

        if (isCaller) {

            listenForAnswer();

            createOffer();

        } else {

            listenForOffer();
        }
    }


    private void createAudio() {

        MediaConstraints audioConstraints =
                new MediaConstraints();


        audioSource =
                peerConnectionFactory.createAudioSource(
                        audioConstraints
                );


        localAudioTrack =
                peerConnectionFactory.createAudioTrack(
                        "LOCAL_AUDIO",
                        audioSource
                );
    }


    private void createCamera() {

        CameraEnumerator cameraEnumerator =
                new Camera2Enumerator(this);


        String[] deviceNames =
                cameraEnumerator.getDeviceNames();


        String frontCameraName = null;


        for (String deviceName : deviceNames) {

            if (cameraEnumerator.isFrontFacing(deviceName)) {

                frontCameraName = deviceName;

                break;
            }
        }


        if (frontCameraName == null &&
                deviceNames.length > 0) {

            frontCameraName = deviceNames[0];
        }


        if (frontCameraName == null) {

            Toast.makeText(
                    this,
                    "Camera not found",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }


        videoCapturer =
                cameraEnumerator.createCapturer(
                        frontCameraName,
                        null
                );


        if (videoCapturer == null) {

            Toast.makeText(
                    this,
                    "Unable to open camera",
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
                peerConnectionFactory.createVideoSource(
                        videoCapturer.isScreencast()
                );


        videoCapturer.initialize(
                surfaceTextureHelper,
                getApplicationContext(),
                videoSource.getCapturerObserver()
        );


        localVideoTrack =
                peerConnectionFactory.createVideoTrack(
                        "LOCAL_VIDEO",
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


        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(
                        iceServers
                );


        peerConnection =
                peerConnectionFactory.createPeerConnection(
                        rtcConfig,
                        new PeerConnection.Observer() {

                            @Override
                            public void onSignalingChange(
                                    PeerConnection.SignalingState state
                            ) {
                            }


                            @Override
                            public void onIceConnectionChange(
                                    PeerConnection.IceConnectionState state
                            ) {
                            }


                            @Override
                            public void onIceConnectionReceivingChange(
                                    boolean receiving
                            ) {
                            }


                            @Override
                            public void onIceGatheringChange(
                                    PeerConnection.IceGatheringState state
                            ) {
                            }


                            @Override
                            public void onIceCandidate(
                                    org.webrtc.IceCandidate candidate
                            ) {

                                if (firestore == null ||
                                        candidate == null) {
                                    return;
                                }


                                Map<String, Object> data =
                                        new HashMap<>();


                                data.put(
                                        "candidate",
                                        candidate.sdp
                                );


                                data.put(
                                        "sdpMid",
                                        candidate.sdpMid
                                );


                                data.put(
                                        "sdpMLineIndex",
                                        candidate.sdpMLineIndex
                                );


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
                                    org.webrtc.IceCandidate[] candidates
                            ) {
                            }


                            @Override
                            public void onAddStream(
                                    org.webrtc.MediaStream stream
                            ) {
                            }


                            @Override
                            public void onRemoveStream(
                                    org.webrtc.MediaStream stream
                            ) {
                            }


                            @Override
                            public void onDataChannel(
                                    org.webrtc.DataChannel dataChannel
                            ) {
                            }


                            @Override
                            public void onRenegotiationNeeded() {
                            }


                            @Override
                            public void onAddTrack(
                                    org.webrtc.RtpReceiver receiver,
                                    org.webrtc.MediaStream[] mediaStreams
                            ) {

                                if (receiver == null) {
                                    return;
                                }


                                org.webrtc.MediaStreamTrack track =
                                        receiver.track();


                                if (track instanceof VideoTrack) {

                                    VideoTrack remoteVideoTrack =
                                            (VideoTrack) track;


                                    runOnUiThread(() ->
                                            remoteVideoTrack.addSink(
                                                    remoteView
                                            )
                                    );
                                }
                            }


                            @Override
                            public void onConnectionChange(
                                    PeerConnection.PeerConnectionState state
                            ) {
                            }


                            @Override
                            public void onSelectedCandidatePairChanged(
                                    org.webrtc.CandidatePairChangeEvent event
                            ) {
                            }


                            @Override
                            public void onIceConnectionReceivingChange(
                                    boolean receiving
                            ) {
                            }
                        }
                );


        if (peerConnection == null) {

            Toast.makeText(
                    this,
                    "Peer connection failed",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }


        if (localAudioTrack != null) {

            peerConnection.addTrack(
                    localAudioTrack
            );
        }


        if (localVideoTrack != null) {

            peerConnection.addTrack(
                    localVideoTrack
            );
        }


        listenForRemoteCandidates();
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
                            SessionDescription offer
                    ) {

                        if (peerConnection == null) {
                            return;
                        }


                        peerConnection.setLocalDescription(
                                new SdpObserver() {

                                    @Override
                                    public void onCreateSuccess(
                                            SessionDescription sdp
                                    ) {
                                    }


                                    @Override
                                    public void onSetSuccess() {

                                        Map<String, Object> offerData =
                                                new HashMap<>();


                                        offerData.put(
                                                "type",
                                                "offer"
                                        );


                                        offerData.put(
                                                "sdp",
                                                offer.description
                                        );


                                        firestore
                                                .collection("calls")
                                                .document(callId)
                                                .set(offerData);
                                    }


                                    @Override
                                    public void onCreateFailure(
                                            String error
                                    ) {
                                    }


                                    @Override
                                    public void onSetFailure(
                                            String error
                                    ) {

                                        runOnUiThread(() ->
                                                Toast.makeText(
                                                        CallActivity.this,
                                                        "Local description failed",
                                                        Toast.LENGTH_LONG
                                                ).show()
                                        );
                                    }
                                },
                                offer
                        );
                    }


                    @Override
                    public void onSetSuccess() {
                    }


                    @Override
                    public void onCreateFailure(
                            String error
                    ) {

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
                            String error
                    ) {
                    }
                },
                constraints
        );
    }


    private void listenForOffer() {

        if (firestore == null) {
            return;
        }


        callListener =
                firestore
                        .collection("calls")
                        .document(callId)
                        .addSnapshotListener(
                                (document, error) -> {

                                    if (error != null ||
                                            document == null ||
                                            !document.exists() ||
                                            offerHandled) {
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


                                    offerHandled = true;


                                    SessionDescription offer =
                                            new SessionDescription(
                                                    SessionDescription.Type.OFFER,
                                                    offerSdp
                                            );


                                    if (peerConnection == null) {
                                        return;
                                    }


                                    peerConnection.setRemoteDescription(
                                            new SdpObserver() {

                                                @Override
                                                public void onSetSuccess() {

                                                    remoteDescriptionSet =
                                                            true;


                                                    flushPendingIceCandidates();


                                                    createAnswer();
                                                }


                                                @Override
                                                public void onCreateSuccess(
                                                        SessionDescription sdp
                                                ) {
                                                }


                                                @Override
                                                public void onCreateFailure(
                                                        String error
                                                ) {
                                                }


                                                @Override
                                                public void onSetFailure(
                                                        String error
                                                ) {

                                                    runOnUiThread(() ->
                                                            Toast.makeText(
                                                                    CallActivity.this,
                                                                    "Offer set failed",
                                                                    Toast.LENGTH_LONG
                                                            ).show()
                                                    );
                                                }
                                            },
                                            offer
                                    );
                                }
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
                            SessionDescription answer
                    ) {

                        if (peerConnection == null) {
                            return;
                        }


                        peerConnection.setLocalDescription(
                                new SdpObserver() {

                                    @Override
                                    public void onCreateSuccess(
                                            SessionDescription sdp
                                    ) {
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
                                            String error
                                    ) {
                                    }


                                    @Override
                                    public void onSetFailure(
                                            String error
                                    ) {

                                        runOnUiThread(() ->
                                                Toast.makeText(
                                                        CallActivity.this,
                                                        "Local answer description failed",
                                                        Toast.LENGTH_LONG
                                                ).show()
                                        );
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
                            String error
                    ) {

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
                            String error
                    ) {
                    }
                },
                constraints
        );
    }


    private void listenForAnswer() {

        if (firestore == null) {
            return;
        }


        callListener =
                firestore
                        .collection("calls")
                        .document(callId)
                        .addSnapshotListener(
                                (document, error) -> {

                                    if (!isCaller ||
                                            error != null ||
                                            document == null ||
                                            !document.exists() ||
                                            answerHandled) {
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


                                    answerHandled = true;


                                    SessionDescription answer =
                                            new SessionDescription(
                                                    SessionDescription.Type.ANSWER,
                                                    answerSdp
                                            );


                                    if (peerConnection == null) {
                                        return;
                                    }


                                    peerConnection.setRemoteDescription(
                                            new SdpObserver() {

                                                @Override
                                                public void onSetSuccess() {

                                                    remoteDescriptionSet =
                                                            true;


                                                    flushPendingIceCandidates();
                                                }


                                                @Override
                                                public void onCreateSuccess(
                                                        SessionDescription sdp
                                                ) {
                                                }


                                                @Override
                                                public void onCreateFailure(
                                                        String error
                                                ) {
                                                }


                                                @Override
                                                public void onSetFailure(
                                                        String error
                                                ) {

                                                    runOnUiThread(() ->
                                                            Toast.makeText(
                                                                    CallActivity.this,
                                                                    "Answer set failed",
                                                                    Toast.LENGTH_LONG
                                                            ).show()
                                                    );
                                                }
                                            },
                                            answer
                                    );
                                }
                        );
    }


    private void listenForRemoteCandidates() {

        if (firestore == null) {
            return;
        }


        String collection =
                isCaller
                        ? "calleeCandidates"
                        : "callerCandidates";


        candidateListener =
                firestore
                        .collection("calls")
                        .document(callId)
                        .collection(collection)
                        .addSnapshotListener(
                                (snapshots, error) -> {

                                    if (error != null ||
                                            snapshots == null) {
                                        return;
                                    }


                                    for (DocumentSnapshot document :
                                            snapshots.getDocuments()) {

                                        String documentId =
                                                document.getId();


                                        if (receivedCandidateIds.contains(
                                                documentId
                                        )) {
                                            continue;
                                        }


                                        String candidateSdp =
                                                document.getString(
                                                        "candidate"
                                                );


                                        String sdpMid =
                                                document.getString(
                                                        "sdpMid"
                                                );


                                        Long sdpMLineIndex =
                                                document.getLong(
                                                        "sdpMLineIndex"
                                                );


                                        if (candidateSdp == null ||
                                                sdpMid == null ||
                                                sdpMLineIndex == null) {
                                            continue;
                                        }


                                        receivedCandidateIds.add(
                                                documentId
                                        );


                                        org.webrtc.IceCandidate candidate =
                                                new org.webrtc.IceCandidate(
                                                        sdpMid,
                                                        sdpMLineIndex.intValue(),
                                                        candidateSdp
                                                );


                                        if (peerConnection == null) {
                                            continue;
                                        }


                                        if (remoteDescriptionSet) {

                                            peerConnection.addIceCandidate(
                                                    candidate
                                            );

                                        } else {

                                            pendingIceCandidates.add(
                                                    candidate
                                            );
                                        }
                                    }
                                }
                        );
    }


    private void flushPendingIceCandidates() {

        if (peerConnection == null) {
            return;
        }


        if (!remoteDescriptionSet) {
            return;
        }


        for (org.webrtc.IceCandidate candidate :
                pendingIceCandidates) {

            peerConnection.addIceCandidate(
                    candidate
            );
        }


        pendingIceCandidates.clear();
    }


    private void toggleMute() {

        if (localAudioTrack == null) {
            return;
        }


        isMuted = !isMuted;


        localAudioTrack.setEnabled(
                !isMuted
        );


        if (isMuted) {

            muteButton.setText("Unmute");

        } else {

            muteButton.setText("Mute");
        }
    }


    private void switchCamera() {

        if (videoCapturer == null) {
            return;
        }


        if (videoCapturer instanceof CameraVideoCapturer) {

            ((CameraVideoCapturer) videoCapturer)
                    .switchCamera(null);
        }
    }


    private void stopCall() {

        if (cleanedUp) {
            return;
        }


        cleanedUp = true;


        if (callListener != null) {

            callListener.remove();

            callListener = null;
        }


        if (candidateListener != null) {

            candidateListener.remove();

            candidateListener = null;
        }


        /*
         * Remove the call document.
         * Firestore candidate subcollections are not
         * automatically deleted when the parent document
         * is deleted.
         */

        if (firestore != null && callId != null) {

            firestore
                    .collection("calls")
                    .document(callId)
                    .delete();
        }


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


        pendingIceCandidates.clear();

        receivedCandidateIds.clear();
    }


    @Override
    protected void onDestroy() {

        stopCall();

        super.onDestroy();
    }
}
