package com.vaibhav.movily;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.File;

public class VideoEditorActivity extends AppCompatActivity {

    private static final String TAG = "VideoEditor";
    private static final int STORAGE_PERMISSION_CODE = 100;

    // Views
    private VideoView videoView;
    private ImageButton btnPlayPause, btnBack;
    private ProgressBar progressBar;
    private SeekBar seekBar;
    private TextView tvTime;

    // State
    private String projectId, videoPath;
    private boolean isPrepared = false;
    private int videoDuration = 0;
    private int currentPosition = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_editor);

        projectId = getIntent().getStringExtra("projectId");
        if (projectId == null) {
            toast("No project selected");
            finish();
            return;
        }

        initViews();
        checkStoragePermission();
        loadProjectData(); // 🔥 Gets videoPath from Firestore (same as CreateProjectActivity)
    }

    private void initViews() {
        videoView = findViewById(R.id.videoView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnBack = findViewById(R.id.btnBack);
        progressBar = findViewById(R.id.progressBar);
        seekBar = findViewById(R.id.seekBar);
        tvTime = findViewById(R.id.tvTime);

        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnBack.setOnClickListener(v -> finish());

        setupSeekBar();
        setupVideoListeners();
    }

    private void checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
        }
    }

    // 🔥 SAME PATTERN AS CREATEPROJECTACTIVITY - Loads from Firestore
    private void loadProjectData() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            toast("❌ Please login first");
            finish();
            return;
        }

        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        Log.d(TAG, "🔄 Loading project: " + projectId + " for user: " + userId);

        FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .collection("projects").document(projectId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        String projectName = documentSnapshot.getString("name");
                        videoPath = documentSnapshot.getString("videoPath"); // 🔥 App private storage path

                        Log.d(TAG, "📁 Video path: " + videoPath);
                        Log.d(TAG, "📁 File exists: " + (videoPath != null ? new File(videoPath).exists() : false));

                        if (projectName != null) {
                            setTitle(projectName);
                        }

                        if (videoPath != null && new File(videoPath).exists()) {
                            loadVideo(videoPath);
                        } else {
                            showError("Video file not found at: " + videoPath);
                        }
                    } else {
                        toast("❌ Project not found");
                        finish();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firestore error", e);
                    toast("❌ Error loading project");
                    finish();
                });
    }

    private void setupVideoListeners() {
        videoView.setOnPreparedListener(mp -> {
            Log.d(TAG, "✅ Video prepared - Duration: " + mp.getDuration() + "ms");
            isPrepared = true;
            videoDuration = mp.getDuration();
            seekBar.setMax(videoDuration);
            hideProgress();
            updateTimeDisplay();
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        });

        videoView.setOnCompletionListener(mp -> {
            Log.d(TAG, "✅ Video completed");
            pauseVideo();
            seekBar.setProgress(0);
            updateTimeDisplay();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "❌ Video error: what=" + what + ", extra=" + extra);
            isPrepared = false;
            hideProgress();
            showError("Video format not supported");
            return true;
        });
    }

    private void setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isPrepared) {
                    currentPosition = Math.max(0, Math.min(progress, videoDuration));
                    videoView.seekTo(currentPosition);
                    updateTimeDisplay();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (isPrepared && videoView.isPlaying()) {
                    pauseVideo();
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isPrepared) {
                    videoView.seekTo(seekBar.getProgress());
                }
            }
        });
    }

    private void loadVideo(String filePath) {
        Log.d(TAG, "🎥 Loading video: " + filePath);
        showProgress();

        try {
            resetVideoView();
            File videoFile = new File(filePath);
            if (!videoFile.exists()) {
                Log.e(TAG, "❌ Video file does not exist: " + filePath);
                showError("Video file missing");
                return;
            }

            Uri videoUri = Uri.fromFile(videoFile);
            videoView.setVideoURI(videoUri);
            videoView.requestFocus();
        } catch (Exception e) {
            Log.e(TAG, "❌ Video load failed", e);
            showError("Failed to load video");
        }
    }

    private void resetVideoView() {
        try {
            if (videoView.isPlaying()) {
                videoView.pause();
            }
            videoView.stopPlayback();
            videoView.suspend();
        } catch (Exception ignored) {}
    }

    private void togglePlayPause() {
        if (!isPrepared) {
            toast("Video not ready");
            return;
        }

        if (videoView.isPlaying()) {
            pauseVideo();
        } else {
            playVideo();
        }
    }

    private void playVideo() {
        videoView.start();
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        startProgressUpdates();
    }

    private void pauseVideo() {
        if (isPrepared && videoView.isPlaying()) {
            videoView.pause();
            currentPosition = videoView.getCurrentPosition();
        }
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        stopProgressUpdates();
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        progressRunnable = this::updateProgress;
        handler.post(progressRunnable);
    }

    private void updateProgress() {
        if (isPrepared && videoView.isPlaying()) {
            currentPosition = videoView.getCurrentPosition();
            seekBar.setProgress(currentPosition);
            updateTimeDisplay();
            handler.postDelayed(progressRunnable, 100);
        }
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
        }
    }

    private void updateTimeDisplay() {
        tvTime.setText(formatTime(currentPosition) + " / " + formatTime(videoDuration));
    }

    private String formatTime(int millis) {
        int seconds = millis / 1000;
        int minutes = seconds / 60;
        seconds %= 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void showError(String msg) {
        Toast.makeText(this, "❌ " + msg, Toast.LENGTH_LONG).show();
    }

    private void showProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void hideProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(android.view.View.GONE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pauseVideo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressUpdates();
        handler.removeCallbacksAndMessages(null);
        resetVideoView();
    }
}
