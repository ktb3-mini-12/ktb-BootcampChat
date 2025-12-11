
package com.ktb.chatapp.service.s3;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.service.FileService;
import com.ktb.chatapp.service.FileUploadResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.UUID;


@Service
@Primary
public class S3FileService implements FileService {

    private final S3Client s3Client;
    private final String bucketName;
    private final FileRepository fileRepository;

    public S3FileService(S3Client s3Client,
                         @Value("${aws.s3.bucket-name}") String bucketName,
                         FileRepository fileRepository) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.fileRepository = fileRepository;
    }

    @Override
    public FileUploadResult uploadFile(MultipartFile multipartFile, String uploaderId) {
        String originalFilename = multipartFile.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String newFileName = UUID.randomUUID().toString() + (extension != null ? "." + extension : "");
        String s3Path = Paths.get("uploads", newFileName).toString();

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Path)
                    .contentType(multipartFile.getContentType())
                    .contentLength(multipartFile.getSize())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(multipartFile.getInputStream(), multipartFile.getSize()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file to S3", e);
        }

        File file = File.builder()
                .filename(newFileName) // Clean filename for API URLs
                .originalname(originalFilename)
                .mimetype(multipartFile.getContentType())
                .size(multipartFile.getSize())
                .path(s3Path) // Full internal path for S3
                .user(uploaderId)
                .build();
        fileRepository.save(file);

        return FileUploadResult.builder()
                .success(true)
                .file(file)
                .build();
    }

    @Override
    public String storeFile(MultipartFile multipartFile, String subDirectory) {
        String originalFilename = multipartFile.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String newFileName = UUID.randomUUID().toString() + (extension != null ? "." + extension : "");
        String key = Paths.get(subDirectory, newFileName).toString();

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(multipartFile.getContentType())
                    .contentLength(multipartFile.getSize())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(multipartFile.getInputStream(), multipartFile.getSize()));
            return newFileName; // Return only the clean filename
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file to S3", e);
        }
    }

    @Override
    public Resource loadFileAsResource(String fileName, String requesterId) {
        File file = fileRepository.findByFilename(fileName)
                .orElseThrow(() -> new RuntimeException("File not found with name " + fileName));

        // Optional: Add authorization logic here if needed.
        // For example, check if requesterId matches file.getUser()

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(file.getPath())
                .build();

        return new InputStreamResource(s3Client.getObject(getObjectRequest));
    }

    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        return fileRepository.findById(fileId).map(file -> {
            // Implement any authorization logic here based on requesterId if needed

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(file.getPath())
                    .build();
            s3Client.deleteObject(deleteObjectRequest);

            fileRepository.delete(file);
            return true;
        }).orElse(false);
    }
}
