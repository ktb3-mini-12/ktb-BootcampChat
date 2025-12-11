package com.ktb.chatapp.service.s3;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.service.FileService;
import com.ktb.chatapp.service.FileUploadResult;
import com.ktb.chatapp.util.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.UUID;


@Slf4j
@Service
@Primary
public class S3FileService implements FileService {

    private final S3Client s3Client;
    private final String bucketName;
    private final String s3PublicUrlPrefix;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;

    public S3FileService(S3Client s3Client,
                         @Value("${aws.s3.bucket-name}") String bucketName,
                         @Qualifier("s3PublicUrlPrefix") String s3PublicUrlPrefix,
                         FileRepository fileRepository,
                         MessageRepository messageRepository,
                         RoomRepository roomRepository) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.s3PublicUrlPrefix = s3PublicUrlPrefix;
        this.fileRepository = fileRepository;
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
    }

    @Override
    public FileUploadResult uploadFile(MultipartFile multipartFile, String uploaderId) {
        try {
            // 파일 보안 검증 (MIME 타입, 크기 제한 등)
            FileUtil.validateFile(multipartFile);

            String originalFilename = multipartFile.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "file";
            }
            originalFilename = StringUtils.cleanPath(originalFilename);

            // 안전한 파일명 생성
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);
            String s3Path = "uploads/" + safeFileName;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Path)
                    .contentType(multipartFile.getContentType())
                    .contentLength(multipartFile.getSize())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(multipartFile.getInputStream(), multipartFile.getSize()));

            log.info("S3 파일 업로드 완료: {}", safeFileName);

            // S3 Public URL 생성
            String publicUrl = s3PublicUrlPrefix + "/" + s3Path;

            // 원본 파일명 정규화
            String normalizedOriginalname = FileUtil.normalizeOriginalFilename(originalFilename);

            File file = File.builder()
                    .filename(safeFileName)
                    .originalname(normalizedOriginalname)
                    .mimetype(multipartFile.getContentType())
                    .size(multipartFile.getSize())
                    .path(s3Path)
                    .url(publicUrl)
                    .user(uploaderId)
                    .build();
            fileRepository.save(file);

            return FileUploadResult.builder()
                    .success(true)
                    .file(file)
                    .build();

        } catch (S3Exception e) {
            log.error("S3 업로드 실패: {}", e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("S3 파일 업로드에 실패했습니다: " + e.awsErrorDetails().errorMessage(), e);
        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 업로드에 실패했습니다: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("파일 업로드 처리 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 업로드에 실패했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public String storeFile(MultipartFile multipartFile, String subDirectory) {
        try {
            // 파일 보안 검증
            FileUtil.validateFile(multipartFile);

            String originalFilename = multipartFile.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "file";
            }
            originalFilename = StringUtils.cleanPath(originalFilename);

            // 안전한 파일명 생성
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);
            String key = subDirectory + "/" + safeFileName;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(multipartFile.getContentType())
                    .contentLength(multipartFile.getSize())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(multipartFile.getInputStream(), multipartFile.getSize()));

            log.info("S3 파일 저장 완료: {}/{}", subDirectory, safeFileName);

            // S3 Public URL 반환
            return s3PublicUrlPrefix + "/" + key;
        } catch (S3Exception e) {
            log.error("S3 저장 실패: {}", e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("S3 파일 저장에 실패했습니다: " + e.awsErrorDetails().errorMessage(), e);
        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 저장에 실패했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public Resource loadFileAsResource(String fileName, String requesterId) {
        // 1. 파일 조회
        File fileEntity = fileRepository.findByFilename(fileName)
                .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다: " + fileName));

        // 2. 메시지 조회 (파일과 메시지 연결 확인)
        Message message = messageRepository.findByFileId(fileEntity.getId())
                .orElseThrow(() -> new RuntimeException("파일과 연결된 메시지를 찾을 수 없습니다"));

        // 3. 방 조회 (사용자가 방 참가자인지 확인)
        Room room = roomRepository.findById(message.getRoomId())
                .orElseThrow(() -> new RuntimeException("방을 찾을 수 없습니다"));

        // 4. 권한 검증 - 방 참가자만 파일 접근 가능
        if (!room.getParticipantIds().contains(requesterId)) {
            log.warn("파일 접근 권한 없음: {} (사용자: {})", fileName, requesterId);
            throw new RuntimeException("파일에 접근할 권한이 없습니다");
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileEntity.getPath())
                    .build();

            log.info("S3 파일 로드 성공: {} (사용자: {})", fileName, requesterId);
            return new InputStreamResource(s3Client.getObject(getObjectRequest));
        } catch (S3Exception e) {
            log.error("S3 파일 로드 실패: {}", e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("파일을 불러오는데 실패했습니다: " + e.awsErrorDetails().errorMessage(), e);
        }
    }

    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        try {
            File fileEntity = fileRepository.findById(fileId)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            // 삭제 권한 검증 (업로더만 삭제 가능)
            if (!fileEntity.getUser().equals(requesterId)) {
                throw new RuntimeException("파일을 삭제할 권한이 없습니다.");
            }

            // S3에서 파일 삭제
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileEntity.getPath())
                    .build();
            s3Client.deleteObject(deleteObjectRequest);

            // 데이터베이스에서 제거
            fileRepository.delete(fileEntity);

            log.info("S3 파일 삭제 완료: {} (사용자: {})", fileId, requesterId);
            return true;

        } catch (S3Exception e) {
            log.error("S3 파일 삭제 실패: {}", e.awsErrorDetails().errorMessage(), e);
            throw new RuntimeException("S3 파일 삭제에 실패했습니다: " + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            log.error("파일 삭제 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 삭제 중 오류가 발생했습니다.", e);
        }
    }
}
