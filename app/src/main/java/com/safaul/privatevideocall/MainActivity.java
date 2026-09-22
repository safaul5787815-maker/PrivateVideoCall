package com.safaul.privatevideocall;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends ComponentActivity {

    private FirebaseAuth auth;
    private FirebaseFirestore firestore;

    private TextView callIdValue;
    private EditText partnerCallId;
    private Button startCallButton;
    private Button endCallButton;

    private String myPairingCode;
    private String myUid;

    private static final String CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        callIdValue = findViewById(R.id.callIdValue);
        partnerCallId = findViewById(R.id.partnerCallId);
        startCallButton = findViewById(R.id.startCallButton);
        endCallButton = findViewById(R.id.endCallButton);

        auth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();

        setupFirebaseLogin();

startCallButton.setOnClickListener(v -> {

    claimPairingCode();

});

        endCallButton.setOnClickListener(v -> {
            endCallButton.setVisibility(View.GONE);
            startCallButton.setVisibility(View.VISIBLE);

            Toast.makeText(
                    this,
                    "Pairing cancelled",
                    Toast.LENGTH_SHORT
            ).show();
        });
    }

    private void setupFirebaseLogin() {

        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null) {
            myUid = currentUser.getUid();
            createOrLoadPairingCode();
            return;
        }

        auth.signInAnonymously()
                .addOnCompleteListener(this, task -> {

                    if (task.isSuccessful()) {

                        FirebaseUser user = auth.getCurrentUser();

                        if (user != null) {
                            myUid = user.getUid();
                            createOrLoadPairingCode();
                        }

                    } else {

                        Toast.makeText(
                                this,
                                "Firebase login failed",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
    }

    private void createOrLoadPairingCode() {

        myPairingCode = generatePairingCode();

        callIdValue.setText(myPairingCode);

        Map<String, Object> data = new HashMap<>();

        data.put("ownerUid", myUid);
        data.put("createdAt", System.currentTimeMillis());

        firestore
                .collection("pairingCodes")
                .document(myPairingCode)
                .set(data)
                .addOnSuccessListener(unused -> {

                    Toast.makeText(
                            this,
                            "Pairing Code ready",
                            Toast.LENGTH_SHORT
                    ).show();

                })
                .addOnFailureListener(e -> {

                    Toast.makeText(
                            this,
                            "Pairing code save failed",
                            Toast.LENGTH_LONG
                    ).show();
                });
    }

    private void claimPairingCode() {

        String partnerCode =
                partnerCallId.getText()
                        .toString()
                        .trim()
                        .toUpperCase();

        if (partnerCode.length() != 8) {

            Toast.makeText(
                    this,
                    "8-character Pairing Code enter karo",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (partnerCode.equals(myPairingCode)) {

            Toast.makeText(
                    this,
                    "Apna khud ka code use nahi kar sakte",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        startCallButton.setEnabled(false);

        firestore
                .collection("pairingCodes")
                .document(partnerCode)
                .get()
                .addOnSuccessListener(document -> {

                    if (!document.exists()) {

                        startCallButton.setEnabled(true);

                        Toast.makeText(
                                this,
                                "Pairing Code nahi mila",
                                Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    String ownerUid =
                            document.getString("ownerUid");

                    if (ownerUid == null ||
                            ownerUid.equals(myUid)) {

                        startCallButton.setEnabled(true);

                        Toast.makeText(
                                this,
                                "Invalid Pairing Code",
                                Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    Map<String, Object> update =
                            new HashMap<>();

                    update.put(
                            "partnerUid",
                            myUid
                    );

                    update.put(
                            "pairedAt",
                            System.currentTimeMillis()
                    );

                    firestore
                            .collection("pairingCodes")
                            .document(partnerCode)
                            .update(update)
                            .addOnSuccessListener(unused -> {

                                createPairDocument(
                                        partnerCode,
                                        ownerUid
                                );

                            })
                            .addOnFailureListener(e -> {

                                startCallButton.setEnabled(true);

                                Toast.makeText(
                                        this,
                                        "Pairing failed",
                                        Toast.LENGTH_LONG
                                ).show();
                            });
                })
                .addOnFailureListener(e -> {

                    startCallButton.setEnabled(true);

                    Toast.makeText(
                            this,
                            "Code check failed",
                            Toast.LENGTH_LONG
                    ).show();
                });
    }

    private void createPairDocument(
            String pairingCode,
            String ownerUid
    ) {

        String pairId;

        if (myUid.compareTo(ownerUid) < 0) {
            pairId = myUid + "_" + ownerUid;
        } else {
            pairId = ownerUid + "_" + myUid;
        }

        Map<String, Object> pairData =
                new HashMap<>();

        pairData.put("ownerUid", ownerUid);
        pairData.put("partnerUid", myUid);
        pairData.put("pairingCode", pairingCode);
        pairData.put(
                "createdAt",
                System.currentTimeMillis()
        );

        firestore
                .collection("pairs")
                .document(pairId)
                .set(pairData)
                .addOnSuccessListener(unused -> {

                    startCallButton.setEnabled(true);

                    startCallButton.setText("Paired ✓");

                    Toast.makeText(
                            this,
                            "Pairing successful ✓",
                            Toast.LENGTH_LONG
                    ).show();

String pairId;

if (myUid.compareTo(ownerUid) < 0) {
    pairId = myUid + "_" + ownerUid;
} else {
    pairId = ownerUid + "_" + myUid;
}

android.content.Intent intent =
        new android.content.Intent(
                MainActivity.this,
                CallActivity.class
        );

intent.putExtra("pairId", pairId);
intent.putExtra("isCaller", false);

startActivity(intent);

                })
                .addOnFailureListener(e -> {

                    startCallButton.setEnabled(true);

                    Toast.makeText(
                            this,
                            "Pair document failed",
                            Toast.LENGTH_LONG
                    ).show();
                });
    }

    private String generatePairingCode() {

        SecureRandom random =
                new SecureRandom();

        StringBuilder code =
                new StringBuilder();

        for (int i = 0; i < 8; i++) {

            int index =
                    random.nextInt(
                            CHARACTERS.length()
                    );

            code.append(
                    CHARACTERS.charAt(index)
            );
        }

        return code.toString();
    }
}
