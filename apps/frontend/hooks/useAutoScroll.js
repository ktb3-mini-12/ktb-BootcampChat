import { useRef, useEffect, useCallback } from 'react';

/**
 * 채팅 메시지 자동 스크롤 훅 (최적화 버전)
 * * @param {Array} messages - 메시지 배열
 * @param {string} currentUserId - 현재 사용자 ID
 * @param {boolean} isLoadingMessages - 이전 메시지 로딩 중 여부
 * @param {number} threshold - 자동 스크롤 임계값 (px, 기본 100)
 * @returns {Object} { containerRef, scrollToBottom, isNearBottom }
 */
export const useAutoScroll = (
  messages = [],
  currentUserId = null,
  isLoadingMessages = false,
  threshold = 100
) => {
  const containerRef = useRef(null);
  const isNearBottomRef = useRef(true);
  const previousMessagesLengthRef = useRef(0);
  const isAutoScrollingRef = useRef(false);

  const previousScrollHeightRef = useRef(0);
  const previousScrollTopRef = useRef(0);
  const isRestoringRef = useRef(false);

  /**
   * 스크롤이 하단 근처에 있는지 확인
   */
  const checkIsNearBottom = useCallback(() => {
    const container = containerRef.current;
    if (!container) return true;

    const { scrollHeight, scrollTop, clientHeight } = container;
    const distanceFromBottom = scrollHeight - (scrollTop + clientHeight);

    return distanceFromBottom <= threshold;
  }, [threshold]);

  /**
   * 최하단으로 스크롤
   * [수정] 기본 behavior를 'auto' (즉시)로 변경하여 E2E 테스트 속도 향상
   * [수정] setTimeout 지연 시간을 줄여서 타이밍 오류 위험 감소
   */
  const scrollToBottom = useCallback((behavior = 'auto') => {
    const container = containerRef.current;
    if (!container) return;

    isAutoScrollingRef.current = true;

    container.scrollTo({
      top: container.scrollHeight,
      behavior
    });

    // behavior가 'smooth'일 때만 약간 기다려주고, 'auto'일 때는 50ms 후 플래그 해제
    const delay = behavior === 'smooth' ? 100 : 50;

    setTimeout(() => {
      isAutoScrollingRef.current = false;
      isNearBottomRef.current = true;
    }, delay);
  }, []);

  /**
   * 스크롤 이벤트 핸들러 - 사용자가 스크롤할 때 위치 추적
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleScroll = () => {
      // 자동 스크롤 중이면 무시
      if (isAutoScrollingRef.current) return;

      isNearBottomRef.current = checkIsNearBottom();
    };

    container.addEventListener('scroll', handleScroll, { passive: true });

    return () => {
      container.removeEventListener('scroll', handleScroll);
    };
  }, [checkIsNearBottom]);

  /**
   * 이전 메시지 로딩 시작 시 스크롤 위치 저장
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    if (isLoadingMessages && !isRestoringRef.current) {
      previousScrollHeightRef.current = container.scrollHeight;
      previousScrollTopRef.current = container.scrollTop;
      isRestoringRef.current = true;
    }
  }, [isLoadingMessages]);

  /**
   * 이전 메시지 로딩 완료 시 스크롤 위치 복원
   */
  useEffect(() => {
    const container = containerRef.current;
    if (!container || !isRestoringRef.current || isLoadingMessages) return;

    const newScrollHeight = container.scrollHeight;
    const heightDifference = newScrollHeight - previousScrollHeightRef.current;

    if (heightDifference > 0) {
      container.scrollTop = previousScrollTopRef.current + heightDifference;
    }

    isRestoringRef.current = false;
  }, [messages, isLoadingMessages]);

  /**
   * 메시지 추가 시 자동 스크롤 로직
   */
  useEffect(() => {
    if (isRestoringRef.current || isLoadingMessages) {
      return;
    }

    if (messages.length === 0 || messages.length === previousMessagesLengthRef.current) {
      return;
    }

    if (messages.length < previousMessagesLengthRef.current) {
      previousMessagesLengthRef.current = messages.length;
      return;
    }

    const newMessages = messages.slice(previousMessagesLengthRef.current);
    previousMessagesLengthRef.current = messages.length;

    if (newMessages.length === 0) return;

    const latestMessage = newMessages[newMessages.length - 1];
    if (!latestMessage) return;

    const senderId = latestMessage.sender?._id || latestMessage.sender?.id || latestMessage.sender;
    const isMyMessage = senderId === currentUserId;

    // [수정] behavior를 'auto'로 호출
    if (isMyMessage) {
      scrollToBottom('auto');
    } else if (isNearBottomRef.current) {
      scrollToBottom('auto');
    }
  }, [messages, currentUserId, scrollToBottom, isLoadingMessages]);

  /**
   * 초기 로드 시 최하단으로 스크롤
   * [수정] 불필요한 100ms setTimeout 제거
   */
  useEffect(() => {
    if (messages.length > 0 && previousMessagesLengthRef.current === 0) {
      // 초기 로드는 즉시 스크롤 (애니메이션 없이)
      scrollToBottom('auto');
    }
  }, [messages.length, scrollToBottom]);

  return {
    containerRef,
    scrollToBottom,
    isNearBottom: () => isNearBottomRef.current
  };
};

export default useAutoScroll;