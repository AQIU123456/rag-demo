package com.example.aichat.repository;

import com.example.aichat.entity.UploadTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UploadTaskRepository extends JpaRepository<UploadTask, Long> {
    Optional<UploadTask> findByTaskId(String taskId);
}
