package com.ktb.chatapp.dto;

import com.ktb.chatapp.model.File;

public record FileResponse (
		String filename,
		String originalname,
		String mimetype,
		long size
) {
    // File 엔티티에서 FileResponse로 변환하는 정적 메서드
    public static FileResponse from(File file) {
        return new FileResponse(
				file.getFilename(),
				file.getOriginalname(),
				file.getMimetype(),
				file.getSize()
		);
    }
}
