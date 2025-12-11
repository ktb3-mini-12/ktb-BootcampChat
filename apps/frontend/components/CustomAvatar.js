import React, { useState, useEffect, useCallback, useMemo, forwardRef } from 'react';
import { Avatar } from '@vapor-ui/core';
import { generateColorFromEmail, getContrastTextColor } from '@/utils/colorUtils';

// [최적화 1] 전역 색상 캐시 (컴포넌트 외부에 선언하여 메모리 공유)
// 이메일이 같으면 무조건 같은 색상이 나오므로 매번 계산할 필요가 없습니다.
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

  // 캐시 저장 (메모리 누수 방지를 위해 사이즈 제한을 둘 수도 있지만, 텍스트라 부담 적음)
  colorCache.set(email, colors);
  return colors;
};

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

  // persistent 모드일 때만 실행되는 로직들
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

  const handleImageError = useCallback((e) => {
    if (!persistent) return; // persistent 아니면 굳이 상태 업데이트 안함 (무한 루프 방지)

    e.preventDefault();
    setImageError(true); // persistent일 때만 fallback으로 전환하기 위함
  }, [persistent]);

  // [최적화 4] 최종 렌더링 값 계산
  const finalImageUrl = useMemo(() => {
    if (!showImage) return undefined;

    // persistent 모드면 상태값 사용, 아니면 바로 계산 (리렌더링 방지)
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
      key={user?._id || user?.id} // key는 여기서 쓰는게 아니라 부모에서 써야하지만 안전장치
      shape="circle"
      size={size}
      render={renderProp}
      src={finalImageUrl}
      className={className}
      style={{
        backgroundColor,
        color,
        cursor: onClick ? 'pointer' : 'default',
        ...style
      }}
      {...props}
    >
      {finalImageUrl && (
        <Avatar.ImagePrimitive
          onError={handleImageError}
          alt={`${user?.name}'s profile`}
          loading="lazy" // [추가] 네이티브 레이지 로딩 적용
        />
      )}
      <Avatar.FallbackPrimitive style={{ backgroundColor, color, fontWeight: '500' }}>
        {initial}
      </Avatar.FallbackPrimitive>
    </Avatar.Root>
  );
});

CustomAvatar.displayName = 'CustomAvatar';

// [최적화 5] React.memo 적용
// user 객체가 바뀌지 않으면 리렌더링 하지 않음
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