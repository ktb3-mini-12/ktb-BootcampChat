import React, { useState, useEffect, useCallback, useMemo, forwardRef } from 'react';
import Image from "next/image"; // Next.js Image 컴포넌트 추가
import { Avatar } from '@vapor-ui/core';
import { generateColorFromEmail, getContrastTextColor } from '@/utils/colorUtils';

// [최적화 1] 전역 색상 캐시 (메모리 공유 및 CPU 연산 최소화)
const colorCache = new Map();

const getCachedColors = (email) => {
  if (!email) return { backgroundColor: '#E0E0E0', color: '#000000' };

  if (colorCache.has(email)) {
    return colorCache.get(email);
  }

  // 캐시에 없으면 계산 후 저장
  const backgroundColor = generateColorFromEmail(email);
  const color = getContrastTextColor(backgroundColor);
  const colors = { backgroundColor, color };

  colorCache.set(email, colors);
  return colors;
};

/**
 * CustomAvatar 컴포넌트 (최적화 버전)
 */
const CustomAvatar = forwardRef(({
  user,
  size = 'md',
  onClick,
  src,
  showImage = true,
  persistent = false,
  showInitials = true,
  className = '',
  style = {},
  ...props
}, ref) => {
  const [currentImage, setCurrentImage] = useState('');
  const [imageError, setImageError] = useState(false);

  // [최적화 2] 색상 계산 메모이제이션 (캐시 조회)
  const { backgroundColor, color } = useMemo(() =>
    getCachedColors(user?.email),
  [user?.email]);

  // [최적화 3] 이미지 URL 생성 로직 메모이제이션
  const getImageUrl = useCallback((imagePath) => {
    if (src) return src;
    if (!imagePath) return null;
    if (imagePath.startsWith('http')) return imagePath;
    return `${process.env.NEXT_PUBLIC_API_URL}${imagePath}`;
  }, [src]);

  // --- persistent 모드 로직 (V1 유지) ---
  useEffect(() => {
    if (!persistent) return;

    const imageUrl = getImageUrl(user?.profileImage);
    if (imageUrl && imageUrl !== currentImage) {
      setImageError(false);
      setCurrentImage(imageUrl);
    } else if (!imageUrl) {
      setCurrentImage('');
    }
  }, [persistent, user?.profileImage, getImageUrl, currentImage]);

  useEffect(() => {
    if (!persistent) return;

    const handleProfileUpdate = () => {
      try {
        const updatedUser = JSON.parse(localStorage.getItem('user') || '{}');
        if (user?.id === updatedUser.id && updatedUser.profileImage !== user.profileImage) {
          const newImageUrl = getImageUrl(updatedUser.profileImage);
          setImageError(false);
          setCurrentImage(newImageUrl);
        }
      } catch (error) {
        console.error('Profile update handling error:', error);
      }
    };

    window.addEventListener('userProfileUpdate', handleProfileUpdate);
    return () => {
      window.removeEventListener('userProfileUpdate', handleProfileUpdate);
    };
  }, [persistent, getImageUrl, user?.id, user?.profileImage]);

  // 이미지 에러 핸들러 (V2의 간결한 버전으로 통일)
  const handleImageError = useCallback(() => {
    if (!persistent) return;

    setImageError(true);
    // V2의 디버그 로그 제거 (프로덕션 환경 최적화)
  }, [persistent]);

  // [최적화 4] 최종 렌더링 URL 메모이제이션
  const finalImageUrl = useMemo(() => {
    if (!showImage) return undefined;

    if (persistent) {
      return currentImage && !imageError ? currentImage : undefined;
    }

    return getImageUrl(user?.profileImage);
  }, [showImage, persistent, currentImage, imageError, user?.profileImage, getImageUrl]);

  const initial = useMemo(() => {
    return showInitials ? (user?.name?.charAt(0)?.toUpperCase() || '?') : '';
  }, [showInitials, user?.name]);

  const renderProp = useMemo(() =>
    onClick ? <button onClick={onClick} type="button" /> : undefined,
  [onClick]);

  return (
    <Avatar.Root
      ref={ref}
      key={user?._id || user?.id}
      shape="circle"
      size={size}
      render={renderProp}
      className={className}
      style={{
        backgroundColor,
        color,
        cursor: onClick ? 'pointer' : 'default',
        // V1의 추가 스타일을 유지합니다.
        ...style
      }}
      {...props}
    >
      {/* V2의 Next/Image 컴포넌트 이식 (Next.js 이미지 최적화 적용) */}
      {finalImageUrl && (
        <Image
          src={finalImageUrl}
          alt={`${user?.name}'s profile`}
          layout="fill"
          objectFit="cover"
          onError={persistent ? handleImageError : undefined}
          // V1의 loading="lazy"는 layout="fill" 시 Next.js가 자동 처리하므로 생략
        />
      )}
      <Avatar.FallbackPrimitive style={{ backgroundColor, color, fontWeight: '500' }}>
        {initial}
      </Avatar.FallbackPrimitive>
    </Avatar.Root>
  );
});

CustomAvatar.displayName = 'CustomAvatar';

// [최적화 5] React.memo 적용 (커스텀 비교 함수)
export default React.memo(CustomAvatar, (prev, next) => {
  return (
    prev.user?.id === next.user?.id &&
    prev.user?.profileImage === next.user?.profileImage &&
    prev.user?.name === next.user?.name &&
    prev.user?.email === next.user?.email &&
    prev.size === next.size &&
    prev.src === next.src &&
    prev.persistent === next.persistent
  );
});