package com.safaul.privatevideocall;

import android.os.Bundle;
import android.text.InputFilter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends ComponentActivity {

    private FirebaseAuth auth;

    private TextView callIdValue;
    private EditText partnerCallId;
    private Button startCallButton;
    private Button endCallButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        callIdValue = findViewById(R.id.callIdValue);
        partnerCallId = findViewById(R.id.partnerCallId);
        startCallButton = findViewById(R.id.startCallButton);
        endCallButton = findViewById(R.id.endCallButton);

        partnerCallId.setFilters(
                new InputFilter[]{
                        new InputFilter.AllCaps()
                }
        );

        auth = FirebaseAuth.getInstance();

        setupFirebaseLogin();

        startCallButton.setOnClickListener(v -> {

            String partnerId =
                    partnerCallId.getText()
                            .toString()
                            .trim();

            if (partnerId.isEmpty()) {

                Toast.makeText(
                        this,
                        "Wife ka Call ID enter karo",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            Toast.makeText(
                    this,
                    "Calling system next step mein connect hoga",
                    Toast.LENGTH_SHORT
            ).show();
        });

        endCallButton.setOnClickListener(v -> {

            Toast.makeText(
                    this,
                    "Call ended",
                    Toast.LENGTH_SHORT
            ).show();

            endCallButton.setVisibility(
                    android.view.View.GONE
            );

            startCallButton.setVisibility(
                    android.view.View.VISIBLE
            );
        });
    }

    private void setupFirebaseLogin() {

        FirebaseUser currentUser =
                auth.getCurrentUser();

        if (currentUser != null) {

            showCallId(currentUser);

        } else {

            auth.signInAnonymously()
                    .addOnCompleteListener(this, task -> {

                        if (task.isSuccessful()) {

                            FirebaseUser user =
                                    auth.getCurrentUser();

                            if (user != null) {
                                showCallId(user);
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
    }

    private void showCallId(FirebaseUser user) {

        String uid = user.getUid();

        /*
         * Temporary display ID.
         * Actual secure pairing will be added
         * with Firestore in the next step.
         */
        String callId =
                uid.substring(
                        0,
                        Math.min(8, uid.length())
                ).toUpperCase();

        callIdValue.setText(callId);
    }
}
