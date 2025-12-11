/** @type {import('next').NextConfig} */

// 환경 변수에서 API URL 가져오기
const apiUrl = process.env.NEXT_PUBLIC_API_URL;
const s3BucketName = process.env.NEXT_PUBLIC_S3_BUCKET_NAME;
const awsRegion = process.env.NEXT_PUBLIC_AWS_REGION || "ap-northeast-2";

let remotePatterns = [];

// API URL이 설정된 경우에만 remotePatterns 구성
if (apiUrl) {
  try {
    const url = new URL(apiUrl);
    remotePatterns.push({
      protocol: url.protocol.replace(":", ""),
      hostname: url.hostname,
      port: url.port || "",
      pathname: "/**", // 해당 호스트의 모든 경로 허용
    });
  } catch (error) {
    console.error("Invalid NEXT_PUBLIC_API_URL for next/image configuration:", error);
  }
}

// S3 버킷 도메인 추가 (환경 변수로 설정된 경우)
if (s3BucketName) {
  remotePatterns.push({
    protocol: "https",
    hostname: `${s3BucketName}.s3.${awsRegion}.amazonaws.com`,
    pathname: "/**",
  });
}

// 기본 S3 패턴 추가 (개발/테스트 환경용)
// Next.js는 와일드카드를 ** 형식으로 지원
remotePatterns.push({
  protocol: "https",
  hostname: "**.s3.**.amazonaws.com",
  pathname: "/**",
});

const nextConfig = {
  reactStrictMode: false, // 에러 처리 문제 해결을 위해 일시적으로 비활성화
  transpilePackages: ["@vapor-ui/core", "@vapor-ui/icons"],
  // Docker 빌드를 위한 standalone 출력 모드 (개발 환경에는 영향 없음)
  output: "standalone",
  // monorepo에서 standalone 빌드 시 중첩 경로 방지
  outputFileTracingRoot: __dirname,
  // next/image를 위한 원격 이미지 소스 설정
  images: {
    remotePatterns,
  },
  // 개발 환경에서의 에러 오버레이 설정
  devIndicators: {
    position: "bottom-right",
  },
  // 개발 환경에서만 더 자세한 에러 로깅
  ...(process.env.NODE_ENV === "development" && {
    experimental: {
      forceSwcTransforms: true,
    },
  }),
};

module.exports = nextConfig;
