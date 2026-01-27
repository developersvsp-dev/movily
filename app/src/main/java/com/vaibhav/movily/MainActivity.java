package com.vaibhav.movily;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.navigation.NavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private RecyclerView rvProjects;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🔥 CHECK AUTH STATE FIRST
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // ✅ LOGGED IN → Edge-to-Edge UI
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 🔹 Initialize Views
        toolbar = findViewById(R.id.toolbar);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        rvProjects = findViewById(R.id.rvProjects); // Add this ID to XML

        // 🔹 Set Toolbar as ActionBar
        setSupportActionBar(toolbar);

        // 🔹 Hamburger Menu Toggle
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.open_drawer, R.string.close_drawer
        );
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // 🔹 Handle Menu Item Clicks
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.menu_home) {
                // Already on home
            }
            else if (id == R.id.menu_projects) {
                startActivity(new Intent(MainActivity.this, ProjectsActivity.class));
            }
            else if (id == R.id.menu_settings) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
            else if (id == R.id.menu_logout) {
                FirebaseAuth.getInstance().signOut();
                startActivity(new Intent(MainActivity.this, LoginActivity.class));
                finish();
            }

            drawerLayout.closeDrawers();
            return true;
        });

        // 🎬 Create New Project Button (KEEP your pink card)
        findViewById(R.id.cardCreateProject).setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, CreateProjectActivity.class));
        });

        // 🔥 LOAD PROJECTS FROM FIRESTORE
        loadProjects();
    }

    private void loadProjects() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        rvProjects.setLayoutManager(new LinearLayoutManager(this));

        FirebaseFirestore.getInstance()
                .collection("users").document(userId).collection("projects")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        List<Project> projects = new ArrayList<>();
                        for (DocumentSnapshot doc : task.getResult()) {
                            Map<String, Object> data = doc.getData();
                            Project project = new Project();
                            project.name = (String) data.get("name");
                            project.projectId = doc.getId();
                            project.videoUri = (String) data.get("videoUri");
                            projects.add(project);
                        }
                        rvProjects.setAdapter(new ProjectsAdapter(projects));
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProjects(); // Refresh when returning
    }
}
