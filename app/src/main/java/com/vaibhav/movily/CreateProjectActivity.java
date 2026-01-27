package com.vaibhav.movily;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;

public class CreateProjectActivity extends AppCompatActivity {

    private TextInputEditText etProjectName;
    private Uri selectedVideoUri;
    private ActivityResultLauncher<Intent> videoPickerLauncher;
    private String projectId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_project);

        etProjectName = findViewById(R.id.etProjectName);

        // 🔥 Video picker launcher
        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        selectedVideoUri = result.getData().getData();
                        Toast.makeText(this, "Video selected! 🎬", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // Select Video button
        findViewById(R.id.cardSelectVideo).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("video/*");
            videoPickerLauncher.launch(intent);
        });

        // Create Project button
        findViewById(R.id.btnCreateProject).setOnClickListener(v -> createProject());
    }

    private void createProject() {
        String projectName = etProjectName.getText().toString().trim();

        if (projectName.isEmpty()) {
            etProjectName.setError("Project name required");
            return;
        }

        if (selectedVideoUri == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }

        // 🔥 Generate unique project ID
        projectId = FirebaseAuth.getInstance().getCurrentUser().getUid() + "_" + System.currentTimeMillis();

        // 🔥 COPY VIDEO TO APP PRIVATE STORAGE (Fixes Google Photos permission crash)
        copyVideoToAppStorage();
    }

    private void copyVideoToAppStorage() {
        // Show loading
        Toast.makeText(this, "Copying video...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // Create app's private videos folder
                File appVideosDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "projects");
                appVideosDir.mkdirs();

                // Create destination file
                File destFile = new File(appVideosDir, projectId + ".mp4");
                String videoPath = destFile.getAbsolutePath();

                // Copy video from source to app storage
                InputStream inputStream = getContentResolver().openInputStream(selectedVideoUri);
                FileOutputStream outputStream = new FileOutputStream(destFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                inputStream.close();
                outputStream.close();

                // ✅ Video copied successfully → Save to Firestore
                runOnUiThread(() -> saveProjectToFirestore(videoPath));

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to copy video: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    private void saveProjectToFirestore(String videoPath) {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        HashMap<String, Object> project = new HashMap<>();
        project.put("name", etProjectName.getText().toString().trim());
        project.put("videoPath", videoPath);  // ✅ REAL FILE PATH - No permission issues!
        project.put("projectId", projectId);
        project.put("createdAt", FieldValue.serverTimestamp());
        project.put("thumbnail", ""); // Generate later

        FirebaseFirestore.getInstance()
                .collection("users").document(userId)
                .collection("projects").document(projectId)
                .set(project)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Project created successfully! 🎉", Toast.LENGTH_SHORT).show();
                    finish(); // Back to MainActivity → Video shows instantly!
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    // Delete copied file if Firestore fails
                    new File(videoPath).delete();
                });
    }
}
