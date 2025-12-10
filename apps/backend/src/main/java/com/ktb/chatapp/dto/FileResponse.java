package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.File;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileResponse {
    private String filename;
    private String originalname;
    private String mimetype;
    private long size;

    // File 엔티티에서 FileResponse로 변환하는 정적 메서드
    public static FileResponse from(File file) {
        return FileResponse.builder()
                .filename(file.getFilename())
                .originalname(file.getOriginalname())
                .mimetype(file.getMimetype())
                .size(file.getSize())
                .build();
    }
}
