package com.vaibhav.movily;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEditorActivity extends AppCompatActivity {

    private static final String TAG = "VideoEditor";
    private static final int STORAGE_PERMISSION_CODE = 100;
    private static final int VIDEO_LOAD_TIMEOUT = 15000;

    // 🔥 ALL VIEWS - NOW TextureView!
    private TextureView videoTextureView;
    private ImageButton btnPlayPause, btnBack;
    private ProgressBar progressBar;
    private SeekBar seekBar, seekBarTrimStart, seekBarTrimEnd;
    private TextView tvTime, tvTrimStart, tvTrimEnd;
    private Button btnTrim, btnToggleTrim, btnCancelTrim;
    private LinearLayout trimControlsContainer;

    // 🔥 MediaPlayer instead of VideoView
    private MediaPlayer mediaPlayer;

    // State
    private String projectId, videoPath;
    private boolean isPrepared = false;
    private boolean isVideoReady = false;
    private int videoDuration = 0;
    private int currentPosition = 0;
    private int trimStart = 0;
    private int trimEnd = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private boolean isPlaying = false;
    private boolean isTrimming = false;
    private boolean isReloadingVideo = false;
    private Runnable loadTimeoutRunnable;
    private Runnable stateMonitorRunnable;
    private Surface videoSurface;

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
        loadProjectData();
    }

    private void initViews() {
        // 🔥 TextureView instead of VideoView
        videoTextureView = findViewById(R.id.videoTextureView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnBack = findViewById(R.id.btnBack);
        progressBar = findViewById(R.id.progressBar);
        seekBar = findViewById(R.id.seekBar);
        tvTime = findViewById(R.id.tvTime);

        trimControlsContainer = findViewById(R.id.trimControlsContainer);
        btnToggleTrim = findViewById(R.id.btnToggleTrim);
        btnCancelTrim = findViewById(R.id.btnCancelTrim);
        seekBarTrimStart = findViewById(R.id.seekBarTrimStart);
        tvTrimStart = findViewById(R.id.tvTrimStart);
        seekBarTrimEnd = findViewById(R.id.seekBarTrimEnd);
        tvTrimEnd = findViewById(R.id.tvTrimEnd);
        btnTrim = findViewById(R.id.btnTrim);

        // 🔥 CRITICAL: Setup TextureView Surface
        if (videoTextureView != null) {
            videoTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                    Log.d(TAG, "🎥 Surface available: " + width + "x" + height);
                    videoSurface = new Surface(surfaceTexture);
                    if (mediaPlayer != null && videoPath != null) {
                        mediaPlayer.setSurface(videoSurface);
                        mediaPlayer.prepareAsync();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    Log.d(TAG, "🎥 Surface destroyed");
                    if (videoSurface != null) {
                        videoSurface.release();
                        videoSurface = null;
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
            });
        }

        // Initial states
        if (btnPlayPause != null) {
            btnPlayPause.setEnabled(false);
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        }
        if (btnToggleTrim != null) {
            btnToggleTrim.setEnabled(false);
            btnToggleTrim.setText("✂️ Show Trim");
        }
        if (btnTrim != null) {
            btnTrim.setText("✂️ Apply Trim");
        }

        // Listeners
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnToggleTrim != null) btnToggleTrim.setOnClickListener(v -> toggleTrimControls());
        if (btnCancelTrim != null) btnCancelTrim.setOnClickListener(v -> hideTrimControls());
        if (btnTrim != null) btnTrim.setOnClickListener(v -> performRealTrim());

        if (trimControlsContainer != null) {
            trimControlsContainer.setVisibility(View.GONE);
        }

        setupSeekBars();
        setupMediaPlayerListeners();
    }

    private void setupMediaPlayerListeners() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(mp -> {
            Log.d(TAG, "🎥 MediaPlayer PREPARED");
            if (loadTimeoutRunnable != null) {
                handler.removeCallbacks(loadTimeoutRunnable);
            }

            handler.postDelayed(() -> {
                try {
                    int duration = mp.getDuration();
                    Log.d(TAG, "✅ Video PREPARED - Duration: " + duration + "ms");

                    if (duration <= 0) {
                        runOnUiThread(() -> showError("Invalid video duration"));
                        return;
                    }

                    isPrepared = true;
                    isVideoReady = true;
                    isReloadingVideo = false;
                    videoDuration = duration;
                    trimStart = 0;
                    trimEnd = videoDuration;
                    currentPosition = 0;

                    runOnUiThread(() -> {
                        if (seekBar != null) {
                            seekBar.setMax(videoDuration);
                            seekBar.setProgress(0);
                        }
                        if (seekBarTrimStart != null) {
                            seekBarTrimStart.setMax(videoDuration);
                            seekBarTrimStart.setProgress(0);
                        }
                        if (seekBarTrimEnd != null) {
                            seekBarTrimEnd.setMax(videoDuration);
                            seekBarTrimEnd.setProgress(videoDuration);
                        }

                        updateDisplays();
                        hideProgress();

                        if (btnPlayPause != null) {
                            btnPlayPause.setEnabled(true);
                            btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                        }
                        if (btnToggleTrim != null) {
                            btnToggleTrim.setEnabled(true);
                        }

                        toast("✅ Video ready!");
                        startStateMonitoring();
                    });

                } catch (Exception e) {
                    Log.e(TAG, "MediaPlayer prepared error", e);
                    isReloadingVideo = false;
                    runOnUiThread(() -> showError("Video setup failed"));
                }
            }, 500);
        });

        mediaPlayer.setOnCompletionListener(mp -> runOnUiThread(() -> {
            Log.d(TAG, "🎥 Video completed");
            pauseVideo();
            if (seekBar != null) seekBar.setProgress(trimStart);
            updateTimeDisplay();
        }));

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "❌ MediaPlayer ERROR: " + what + ", extra: " + extra);
            runOnUiThread(() -> {
                if (loadTimeoutRunnable != null) {
                    handler.removeCallbacks(loadTimeoutRunnable);
                }
                resetVideoState();
                hideProgress();
                showError("Playback error: " + what);
            });
            return true;
        });

        mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
            Log.d(TAG, "✅ Video size: " + width + "x" + height);
        });
    }

    private void startStateMonitoring() {
        stopStateMonitoring();
        stateMonitorRunnable = this::monitorVideoState;
        handler.post(stateMonitorRunnable);
    }

    private void stopStateMonitoring() {
        if (stateMonitorRunnable != null) {
            handler.removeCallbacks(stateMonitorRunnable);
            stateMonitorRunnable = null;
        }
    }

    private void monitorVideoState() {
        if (mediaPlayer != null && isVideoReady) {
            boolean actualPlaying = mediaPlayer.isPlaying();
            if (actualPlaying != isPlaying) {
                Log.d(TAG, "🔄 STATE SYNC: flag=" + isPlaying + " → actual=" + actualPlaying);
                isPlaying = actualPlaying;
                runOnUiThread(() -> {
                    if (btnPlayPause != null) {
                        btnPlayPause.setImageResource(isPlaying ?
                                android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                    }
                });
            }
        }
        handler.postDelayed(stateMonitorRunnable, 250);
    }

    private void loadVideo(String filePath) {
        if (isReloadingVideo) {
            Log.d(TAG, "⏳ Already loading video");
            return;
        }

        Log.d(TAG, "🎥 Loading video: " + filePath);
        isReloadingVideo = true;
        resetVideoState();

        runOnUiThread(() -> {
            if (btnPlayPause != null) btnPlayPause.setEnabled(false);
            if (btnToggleTrim != null) btnToggleTrim.setEnabled(false);
            showProgress();
        });

        handler.post(() -> {
            File videoFile = new File(filePath);
            if (!videoFile.exists()) {
                runOnUiThread(() -> {
                    showError("Video file not found: " + filePath);
                    isReloadingVideo = false;
                });
                return;
            }

            if (videoFile.length() < 1024) {
                runOnUiThread(() -> {
                    showError("Invalid video file: " + videoFile.length() + " bytes");
                    isReloadingVideo = false;
                });
                return;
            }

            try {
                startLoadTimeout();

                // 🔥 Reset and prepare MediaPlayer
                if (mediaPlayer != null) {
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(videoFile.getAbsolutePath());
                    if (videoSurface != null) {
                        mediaPlayer.setSurface(videoSurface);
                    }
                    mediaPlayer.prepareAsync();
                    Log.d(TAG, "🎥 MediaPlayer prepared async");
                }
            } catch (IOException e) {
                Log.e(TAG, "❌ MediaPlayer prepare error", e);
                cancelLoadTimeout();
                runOnUiThread(() -> {
                    showError("Unsupported video format");
                    isReloadingVideo = false;
                });
            }
        });
    }

    private void resetVideoState() {
        isPrepared = false;
        isVideoReady = false;
        isPlaying = false;
        videoDuration = 0;
        currentPosition = 0;
        trimStart = 0;
        trimEnd = 0;
        stopStateMonitoring();
    }

    private void startLoadTimeout() {
        cancelLoadTimeout();
        loadTimeoutRunnable = () -> {
            Log.e(TAG, "⏰ Video load timeout");
            runOnUiThread(() -> {
                resetVideoState();
                hideProgress();
                showError("Video load timeout");
            });
            isReloadingVideo = false;
        };
        handler.postDelayed(loadTimeoutRunnable, VIDEO_LOAD_TIMEOUT);
    }

    private void cancelLoadTimeout() {
        if (loadTimeoutRunnable != null) {
            handler.removeCallbacks(loadTimeoutRunnable);
            loadTimeoutRunnable = null;
        }
    }

    private void loadProjectData() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            toast("❌ Please login first");
            finish();
            return;
        }

        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        Log.d(TAG, "🔄 Loading project: " + projectId);

        FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .collection("projects").document(projectId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        String projectName = documentSnapshot.getString("name");
                        videoPath = documentSnapshot.getString("videoPath");

                        if (projectName != null) {
                            setTitle(projectName);
                        }

                        if (videoPath != null && new File(videoPath).exists()) {
                            Log.d(TAG, "✅ Video path valid: " + videoPath);
                            loadVideo(videoPath);
                        } else {
                            showError("Video file missing: " + videoPath);
                        }
                    } else {
                        toast("❌ Project not found");
                        finish();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firestore error", e);
                    toast("❌ Error loading project");
                });
    }

    private void togglePlayPause() {
        Log.d(TAG, "▶️ Toggle play - ready:" + isVideoReady + " playing:" + isPlaying);

        if (isTrimming || isReloadingVideo) {
            toast("⏳ Please wait...");
            return;
        }

        if (!isVideoReady) {
            toast("🔄 Loading video...");
            return;
        }

        if (isPlaying) {
            pauseVideo();
        } else {
            playVideo();
        }
    }

    private void playVideo() {
        if (!isVideoReady || mediaPlayer == null) {
            Log.w(TAG, "Cannot play - not ready");
            return;
        }

        try {
            Log.d(TAG, "▶️ Playing from: " + currentPosition);
            int seekPos = Math.max(trimStart, currentPosition);

            mediaPlayer.seekTo(seekPos);
            mediaPlayer.start();

            isPlaying = true;
            if (btnPlayPause != null) {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            }
            startProgressUpdates();

        } catch (Exception e) {
            Log.e(TAG, "Play error", e);
            isPlaying = false;
        }
    }

    private void pauseVideo() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                currentPosition = mediaPlayer.getCurrentPosition();
            }
            isPlaying = false;
            if (btnPlayPause != null) {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }
            stopProgressUpdates();
            Log.d(TAG, "⏸️ Paused at: " + currentPosition);
        } catch (Exception e) {
            Log.e(TAG, "Pause error", e);
        }
    }

    private void toggleTrimControls() {
        if (trimControlsContainer == null) return;
        if (trimControlsContainer.getVisibility() == View.VISIBLE) {
            hideTrimControls();
        } else {
            showTrimControls();
        }
    }

    private void showTrimControls() {
        if (!isPrepared || trimControlsContainer == null) {
            toast("⏳ Wait for video to load");
            return;
        }

        trimControlsContainer.setVisibility(View.VISIBLE);
        trimControlsContainer.setAlpha(0f);
        trimControlsContainer.setTranslationY(100f);
        trimControlsContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start();

        if (btnToggleTrim != null) {
            btnToggleTrim.setText("⏫ Hide Trim");
        }
        pauseVideo();

        trimStart = 0;
        trimEnd = videoDuration;
        updateDisplays();
    }

    private void hideTrimControls() {
        if (trimControlsContainer == null) return;
        trimControlsContainer.animate()
                .alpha(0f)
                .translationY(100f)
                .setDuration(250)
                .withEndAction(() -> trimControlsContainer.setVisibility(View.GONE))
                .start();
        if (btnToggleTrim != null) {
            btnToggleTrim.setText("✂️ Show Trim");
        }
    }

    // 🔥 Trim methods (unchanged)
    private void performRealTrim() {
        if (!isPrepared || trimStart >= trimEnd || (trimEnd - trimStart) < 1000) {
            toast("❌ Invalid trim range (min 1 sec)");
            return;
        }

        if (isTrimming) {
            toast("⏳ Already trimming...");
            return;
        }

        isTrimming = true;
        if (btnTrim != null) {
            btnTrim.setText("⏳ Trimming...");
            btnTrim.setEnabled(false);
        }
        if (btnCancelTrim != null) {
            btnCancelTrim.setEnabled(false);
        }
        showProgress();
        pauseVideo();

        File originalFile = new File(videoPath);
        if (!originalFile.exists()) {
            finishTrimming();
            toast("❌ Video file missing");
            return;
        }

        String fileName = originalFile.getName();
        String nameWithoutExt = fileName.contains(".") ?
                fileName.substring(0, fileName.lastIndexOf(".")) : fileName;
        String ext = fileName.contains(".") ?
                fileName.substring(fileName.lastIndexOf(".")) : ".mp4";
        String trimmedPath = originalFile.getParent() + "/" +
                nameWithoutExt + "_trimmed_" + System.currentTimeMillis() + ext;

        new Thread(() -> {
            try {
                boolean success = trimVideo(originalFile.getAbsolutePath(), trimmedPath,
                        trimStart / 1000f, (trimEnd - trimStart) / 1000f);

                if (success) {
                    File trimmedFile = new File(trimmedPath);
                    if (trimmedFile.exists() && trimmedFile.length() > 1024) {
                        File backupFile = new File(videoPath + ".bak");
                        if (originalFile.renameTo(backupFile)) {
                            if (trimmedFile.renameTo(new File(videoPath))) {
                                if (backupFile.exists()) backupFile.delete();
                                runOnUiThread(() -> {
                                    loadVideo(videoPath);
                                    updateProjectVideoPath(videoPath);
                                    hideTrimControls();
                                    toast("✅ Trim complete!");
                                });
                            } else {
                                backupFile.renameTo(originalFile);
                                runOnUiThread(() -> toast("❌ Replace failed"));
                            }
                        } else {
                            runOnUiThread(() -> toast("❌ Backup failed"));
                        }
                    } else {
                        runOnUiThread(() -> toast("❌ Trimmed file invalid"));
                    }
                } else {
                    runOnUiThread(() -> toast("❌ Trim processing failed"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Trim exception", e);
                runOnUiThread(() -> toast("❌ Trim failed: " + e.getMessage()));
            } finally {
                runOnUiThread(this::finishTrimming);
            }
        }).start();
    }

    private boolean trimVideo(String inputPath, String outputPath, float startMs, float durationMs) {
        MediaMuxer muxer = null;
        MediaExtractor extractor = null;
        try {
            new File(outputPath).delete();

            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int videoTrackIndex = -1, audioTrackIndex = -1;
            MediaFormat videoFormat = null, audioFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i;
                    videoFormat = format;
                } else if (mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found");
                return false;
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOutputTrack = muxer.addTrack(videoFormat);
            int audioOutputTrack = audioTrackIndex != -1 ? muxer.addTrack(audioFormat) : -1;
            muxer.start();

            long startTimeUs = (long)(startMs * 1000000L);
            long endTimeUs = startTimeUs + (long)(durationMs * 1000000L);

            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Video track
            {
                extractor.release();
                extractor = new MediaExtractor();
                extractor.setDataSource(inputPath);
                extractor.selectTrack(videoTrackIndex);

                long firstVideoPts = -1;
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    long pts = extractor.getSampleTime();
                    if (pts >= endTimeUs) break;

                    if (firstVideoPts < 0) firstVideoPts = pts;

                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = pts - firstVideoPts;
                    bufferInfo.offset = 0;
                    bufferInfo.flags = extractor.getSampleFlags();

                    muxer.writeSampleData(videoOutputTrack, buffer, bufferInfo);
                    extractor.advance();
                }
            }

            // Audio track
            if (audioOutputTrack != -1) {
                extractor.release();
                extractor = new MediaExtractor();
                extractor.setDataSource(inputPath);
                extractor.selectTrack(audioTrackIndex);

                long firstAudioPts = -1;
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                while (true) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;

                    long pts = extractor.getSampleTime();
                    if (pts >= endTimeUs) break;

                    if (firstAudioPts < 0) firstAudioPts = pts;

                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = pts - firstAudioPts;
                    bufferInfo.offset = 0;
                    bufferInfo.flags = extractor.getSampleFlags();

                    muxer.writeSampleData(audioOutputTrack, buffer, bufferInfo);
                    extractor.advance();
                }
            }

            muxer.stop();
            muxer.release();

            File outputFile = new File(outputPath);
            boolean success = outputFile.exists() && outputFile.length() > 1024;
            Log.d(TAG, "Trim result: " + success + " size: " + outputFile.length());
            return success;

        } catch (Exception e) {
            Log.e(TAG, "TrimVideo error", e);
            return false;
        } finally {
            try {
                if (extractor != null) extractor.release();
                if (muxer != null) muxer.release();
            } catch (Exception ignored) {}
        }
    }

    // 🔥 Rest of methods unchanged
    private void setupSeekBars() {
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isVideoReady && mediaPlayer != null) {
                        currentPosition = Math.max(trimStart, Math.min(progress, trimEnd));
                        mediaPlayer.seekTo(currentPosition);
                        updateTimeDisplay();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    if (isPlaying) pauseVideo();
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (isVideoReady && mediaPlayer != null) {
                        mediaPlayer.seekTo(seekBar.getProgress());
                    }
                }
            });
        }

        if (seekBarTrimStart != null) {
            seekBarTrimStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isPrepared) {
                        trimStart = Math.min(progress, trimEnd - 1000);
                        if (seekBar != null) {
                            seekBar.setProgress(Math.max(seekBar.getProgress(), trimStart));
                        }
                        if (currentPosition < trimStart) {
                            currentPosition = trimStart;
                            if (seekBar != null) seekBar.setProgress(trimStart);
                        }
                        updateDisplays();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (seekBarTrimEnd != null) {
            seekBarTrimEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isPrepared) {
                        trimEnd = Math.max(progress, trimStart + 1000);
                        if (seekBar != null) {
                            seekBar.setProgress(Math.min(seekBar.getProgress(), trimEnd));
                        }
                        if (currentPosition > trimEnd) {
                            currentPosition = trimEnd;
                            if (seekBar != null) seekBar.setProgress(trimEnd);
                        }
                        updateDisplays();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    private void updateDisplays() {
        runOnUiThread(() -> {
            updateTimeDisplay();
            updateTrimDisplays();
        });
    }

    private void updateTrimDisplays() {
        if (tvTrimStart != null) tvTrimStart.setText(formatTime(trimStart));
        if (tvTrimEnd != null) tvTrimEnd.setText(formatTime(trimEnd));
    }

    private void updateTimeDisplay() {
        int elapsed = Math.max(0, currentPosition - trimStart);
        int total = trimEnd - trimStart;
        if (tvTime != null) {
            tvTime.setText(formatTime(elapsed) + " / " + formatTime(total));
        }
    }

    private String formatTime(int millis) {
        int seconds = millis / 1000;
        int minutes = seconds / 60;
        seconds %= 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        progressRunnable = this::updateProgress;
        handler.post(progressRunnable);
    }

    private void updateProgress() {
        if (!isPlaying || !isVideoReady || mediaPlayer == null) {
            stopProgressUpdates();
            return;
        }

        try {
            currentPosition = mediaPlayer.getCurrentPosition();
            if (currentPosition >= trimEnd) {
                pauseVideo();
                return;
            }

            runOnUiThread(() -> {
                if (seekBar != null) {
                    seekBar.setProgress(currentPosition);
                }
                updateTimeDisplay();
            });

            handler.postDelayed(progressRunnable, 50);
        } catch (Exception e) {
            Log.e(TAG, "Progress update error", e);
            stopProgressUpdates();
        }
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void finishTrimming() {
        isTrimming = false;
        runOnUiThread(() -> {
            if (btnTrim != null) {
                btnTrim.setEnabled(true);
                btnTrim.setText("✂️ Apply Trim");
            }
            if (btnCancelTrim != null) {
                btnCancelTrim.setEnabled(true);
            }
            hideProgress();
        });
    }

    private void updateProjectVideoPath(String newPath) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .collection("projects").document(projectId)
                .update("videoPath", newPath)
                .addOnFailureListener(e -> Log.e(TAG, "Firestore update failed", e));
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showError(String msg) {
        runOnUiThread(() -> Toast.makeText(this, "❌ " + msg, Toast.LENGTH_LONG).show());
    }

    private void showProgress() {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideProgress() {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
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
        cancelLoadTimeout();
        stopStateMonitoring();
        resetVideoState();

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadProjectData();
        } else {
            toast("❌ Storage permission required");
            finish();
        }
    }
}
