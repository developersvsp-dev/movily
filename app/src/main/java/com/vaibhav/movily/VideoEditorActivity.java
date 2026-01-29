package com.vaibhav.movily;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
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
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEditorActivity extends AppCompatActivity {

    private static final String TAG = "VideoEditor";
    private static final int STORAGE_PERMISSION_CODE = 100;

    // Views
    private VideoView videoView;
    private ImageButton btnPlayPause, btnBack;
    private ProgressBar progressBar;
    private SeekBar seekBar, seekBarTrimStart, seekBarTrimEnd;
    private TextView tvTime, tvTrimStart, tvTrimEnd;
    private Button btnTrim;

    // State
    private String projectId, videoPath;
    private boolean isPrepared = false;
    private int videoDuration = 0;
    private int currentPosition = 0;
    private int trimStart = 0;  // milliseconds
    private int trimEnd = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private boolean isTrimming = false;
    private boolean isReloadingVideo = false;

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
        videoView = findViewById(R.id.videoView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnBack = findViewById(R.id.btnBack);
        progressBar = findViewById(R.id.progressBar);
        seekBar = findViewById(R.id.seekBar);
        tvTime = findViewById(R.id.tvTime);
        seekBarTrimStart = findViewById(R.id.seekBarTrimStart);
        tvTrimStart = findViewById(R.id.tvTrimStart);
        seekBarTrimEnd = findViewById(R.id.seekBarTrimEnd);
        tvTrimEnd = findViewById(R.id.tvTrimEnd);
        btnTrim = findViewById(R.id.btnTrim);

        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnBack.setOnClickListener(v -> finish());
        btnTrim.setOnClickListener(v -> performRealTrim());

        setupSeekBars();
        setupVideoListeners();
    }

    /** 🔥 FULLY FIXED ANDROID-NATIVE VIDEO TRIMMER */
    private void performRealTrim() {
        if (!isPrepared || trimStart >= trimEnd || (trimEnd - trimStart) < 1000) {
            toast("❌ Select valid trim range (min 1 second)");
            return;
        }

        if (isTrimming) {
            toast("⏳ Trimming in progress...");
            return;
        }

        isTrimming = true;
        btnTrim.setText("⏳ Trimming...");
        btnTrim.setEnabled(false);
        showProgress();

        File originalFile = new File(videoPath);
        if (!originalFile.exists()) {
            runOnUiThread(() -> {
                toast("❌ Original video not found");
                finishTrimming();
            });
            return;
        }

        // 🔥 FIXED: Unique filename with proper extension handling
        String fileName = originalFile.getName();
        String nameWithoutExt = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf(".")) : fileName;
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : ".mp4";
        String trimmedPath = originalFile.getParent() + "/" + nameWithoutExt + "_trimmed_" + System.currentTimeMillis() + ext;

        new Thread(() -> {
            try {
                boolean success = trimVideo(originalFile.getAbsolutePath(), trimmedPath,
                        trimStart / 1000f, (trimEnd - trimStart) / 1000f);

                if (success) {
                    File trimmedFile = new File(trimmedPath);
                    if (trimmedFile.exists() && trimmedFile.length() > 0) {
                        // 🔥 FIXED: Proper backup and replace logic
                        File backupFile = new File(videoPath + ".bak");
                        if (originalFile.renameTo(backupFile)) {
                            if (trimmedFile.renameTo(new File(videoPath))) {
                                // Clean up backup
                                if (backupFile.exists()) {
                                    backupFile.delete();
                                }
                                runOnUiThread(() -> {
                                    loadVideo(videoPath);
                                    updateProjectVideoPath(videoPath);
                                    toast("✅ Trimmed to " + formatTime(trimEnd - trimStart));
                                });
                            } else {
                                // Restore backup on failure
                                backupFile.renameTo(originalFile);
                                runOnUiThread(() -> toast("❌ Failed to replace video"));
                            }
                        } else {
                            runOnUiThread(() -> toast("❌ Failed to backup original"));
                        }
                    } else {
                        runOnUiThread(() -> toast("❌ Trimmed file is invalid"));
                    }
                } else {
                    runOnUiThread(() -> toast("❌ Trim failed - check video format"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Trim error", e);
                runOnUiThread(() -> toast("❌ Trim failed: " + e.getMessage()));
            } finally {
                runOnUiThread(this::finishTrimming);
            }
        }).start();
    }

    /** 🔥 FULLY FIXED CORE VIDEO TRIMMING FUNCTION */
    private boolean trimVideo(String inputPath, String outputPath, float startMs, float durationMs) {
        MediaMuxer muxer = null;
        MediaExtractor extractor = null;
        try {
            File outputFile = new File(outputPath);
            if (outputFile.exists()) outputFile.delete();

            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int videoTrackIndex = -1;
            int audioTrackIndex = -1;
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;

            // 🔥 FIXED: Find video and audio tracks properly
            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) {
                Log.e(TAG, "No tracks found");
                return false;
            }

            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null) {
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i;
                        videoFormat = format;
                        Log.d(TAG, "Found video track " + i + ": " + mime);
                    } else if (mime.startsWith("audio/")) {
                        audioTrackIndex = i;
                        audioFormat = format;
                        Log.d(TAG, "Found audio track " + i + ": " + mime);
                    }
                }
            }

            if (videoTrackIndex == -1) {
                Log.e(TAG, "No video track found");
                return false;
            }

            // 🔥 FIXED: Proper buffer size and muxer setup
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            int audioTrack = (audioTrackIndex != -1 && audioFormat != null) ? muxer.addTrack(audioFormat) : -1;

            muxer.start();

            // 🔥 FIXED: Precise time calculations
            long startTimeUs = (long) (startMs * 1000000.0);
            long endTimeUs = startTimeUs + (long) (durationMs * 1000000.0);

            // Reset extractor for processing
            extractor.release();
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            // 🔥 FIXED: Process video track
            extractor.selectTrack(videoTrackIndex);
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean videoEOS = false;
            long videoStartTimeUs = -1;

            while (!videoEOS) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    videoEOS = true;
                    break;
                }

                long presentationTimeUs = extractor.getSampleTime();

                // 🔥 FIXED: Proper time range checking
                if (presentationTimeUs < startTimeUs) {
                    extractor.advance();
                    continue;
                }

                if (presentationTimeUs >= endTimeUs) {
                    break;
                }

                if (videoStartTimeUs < 0) {
                    videoStartTimeUs = presentationTimeUs;
                }

                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = presentationTimeUs - videoStartTimeUs;
                bufferInfo.offset = 0;
                bufferInfo.flags = extractor.getSampleFlags();

                muxer.writeSampleData(videoTrack, buffer, bufferInfo);
                extractor.advance();
            }

            // 🔥 FIXED: Process audio track if present
            if (audioTrack != -1 && audioTrackIndex != -1) {
                extractor.selectTrack(audioTrackIndex);
                extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                extractor.unselectTrack(videoTrackIndex); // Unselect video for audio processing

                boolean audioEOS = false;
                long audioStartTimeUs = -1;

                while (!audioEOS) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        audioEOS = true;
                        break;
                    }

                    long presentationTimeUs = extractor.getSampleTime();

                    if (presentationTimeUs < startTimeUs) {
                        extractor.advance();
                        continue;
                    }

                    if (presentationTimeUs >= endTimeUs) {
                        break;
                    }

                    if (audioStartTimeUs < 0) {
                        audioStartTimeUs = presentationTimeUs;
                    }

                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = presentationTimeUs - audioStartTimeUs;
                    bufferInfo.offset = 0;
                    bufferInfo.flags = extractor.getSampleFlags();

                    muxer.writeSampleData(audioTrack, buffer, bufferInfo);
                    extractor.advance();
                }
            }

            muxer.stop();
            muxer.release();
            muxer = null;

            // 🔥 FIXED: Verify output file
            File output = new File(outputPath);
            if (output.exists() && output.length() > 1024) {
                Log.d(TAG, "✅ Trim successful. Output: " + output.length() + " bytes");
                return true;
            } else {
                Log.e(TAG, "❌ Output file invalid: " + outputPath);
                if (output.exists()) output.delete();
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Trim video error", e);
            return false;
        } finally {
            try {
                if (extractor != null) {
                    extractor.release();
                }
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ===== REST OF METHODS (MINOR FIXES APPLIED) =====

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
        }
    }

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
                        videoPath = documentSnapshot.getString("videoPath");

                        Log.d(TAG, "📁 Video path: " + videoPath);

                        if (projectName != null) {
                            setTitle(projectName);
                        }

                        if (videoPath != null && new File(videoPath).exists()) {
                            loadVideo(videoPath);
                        } else {
                            showError("Video file not found");
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

    private void setupVideoListeners() {
        videoView.setOnPreparedListener(mp -> {
            handler.post(() -> {
                Log.d(TAG, "✅ Video prepared - Duration: " + mp.getDuration() + "ms");
                isPrepared = true;
                videoDuration = mp.getDuration();

                trimStart = 0;
                trimEnd = videoDuration;

                seekBar.setMax(videoDuration);
                seekBarTrimStart.setMax(videoDuration);
                seekBarTrimEnd.setMax(videoDuration);
                seekBarTrimStart.setProgress(0);
                seekBarTrimEnd.setProgress(videoDuration);

                currentPosition = 0;
                updateDisplays();
                hideProgress();
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            });
        });

        videoView.setOnCompletionListener(mp -> {
            pauseVideo();
            seekBar.setProgress(trimStart);
            updateTimeDisplay();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "Video error: " + what + ", " + extra);
            isPrepared = false;
            hideProgress();
            showError("Video playback error");
            return true;
        });
    }

    private void setupSeekBars() {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isPrepared) {
                    currentPosition = Math.max(trimStart, Math.min(progress, trimEnd));
                    videoView.seekTo(currentPosition);
                    updateTimeDisplay();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (isPrepared && videoView.isPlaying()) pauseVideo();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (isPrepared) videoView.seekTo(seekBar.getProgress());
            }
        });

        seekBarTrimStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isPrepared) {
                    trimStart = Math.min(progress, trimEnd - 1000);
                    seekBarTrimStart.setProgress(trimStart);
                    if (currentPosition < trimStart) {
                        currentPosition = trimStart;
                        seekBar.setProgress(trimStart);
                    }
                    updateDisplays();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekBarTrimEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && isPrepared) {
                    trimEnd = Math.max(progress, trimStart + 1000);
                    seekBarTrimEnd.setProgress(trimEnd);
                    if (currentPosition > trimEnd) {
                        currentPosition = trimEnd;
                        seekBar.setProgress(trimEnd);
                    }
                    updateDisplays();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateDisplays() {
        updateTrimDisplays();
        updateTimeDisplay();
    }

    private void updateTrimDisplays() {
        tvTrimStart.setText(formatTime(trimStart));
        tvTrimEnd.setText(formatTime(trimEnd));
    }

    private void finishTrimming() {
        isTrimming = false;
        btnTrim.setEnabled(true);
        btnTrim.setText("✂️ Trim & Replace Video");
        hideProgress();
    }

    private void updateProjectVideoPath(String newPath) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .collection("projects").document(projectId)
                .update("videoPath", newPath)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to update video path", e));
    }

    private void loadVideo(String filePath) {
        if (isReloadingVideo) return;
        Log.d(TAG, "🎥 Loading: " + filePath);
        isReloadingVideo = true;

        handler.post(() -> {
            resetVideoView();
            File videoFile = new File(filePath);
            if (!videoFile.exists()) {
                showError("Video file missing");
                isReloadingVideo = false;
                return;
            }

            Uri videoUri = Uri.fromFile(videoFile);
            videoView.setVideoURI(videoUri);
            videoView.requestFocus();
            showProgress();

            handler.postDelayed(() -> {
                hideProgress();
                isReloadingVideo = false;
            }, 1500);
        });
    }

    private void togglePlayPause() {
        if (!isPrepared || isTrimming || isReloadingVideo) {
            toast("Video not ready");
            return;
        }
        if (videoView.isPlaying()) pauseVideo();
        else playVideo();
    }

    private void playVideo() {
        videoView.seekTo(trimStart);
        videoView.start();
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        startProgressUpdates();
    }

    private void pauseVideo() {
        if (videoView.isPlaying()) {
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
        if (isPrepared && videoView.isPlaying() && currentPosition < trimEnd) {
            currentPosition = videoView.getCurrentPosition();
            if (currentPosition >= trimEnd) {
                pauseVideo();
                return;
            }
            seekBar.setProgress(currentPosition);
            updateTimeDisplay();
            handler.postDelayed(progressRunnable, 100);
        }
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void updateTimeDisplay() {
        int elapsed = Math.max(0, currentPosition - trimStart);
        int total = trimEnd - trimStart;
        tvTime.setText(formatTime(elapsed) + " / " + formatTime(total));
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
        if (progressBar != null) progressBar.setVisibility(android.view.View.VISIBLE);
    }

    private void hideProgress() {
        if (progressBar != null) progressBar.setVisibility(android.view.View.GONE);
    }

    private void resetVideoView() {
        try {
            if (videoView != null) {
                if (videoView.isPlaying()) videoView.pause();
                videoView.stopPlayback();
                videoView.suspend();
            }
        } catch (Exception e) {
            Log.w(TAG, "VideoView reset ignored", e);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadProjectData();
            } else {
                toast("❌ Storage permission required");
                finish();
            }
        }
    }
}
