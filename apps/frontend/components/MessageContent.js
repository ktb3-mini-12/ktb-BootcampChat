import React, { useMemo } from 'react';
import { Text, Badge } from '@vapor-ui/core';

// [개선] 정규식 객체를 컴포넌트 외부로 이동 (CPU 낭비 방지)
const MENTION_PATTERN = /@([\w.-]+)/g;

// 멘션 패턴을 찾아서 React 엘리먼트로 변환하는 함수 (순수 함수화)
const parseTextWithMentions = (text) => {
  if (!text.includes('@')) return text;

  const parts = [];
  let lastIndex = 0;
  let match;

  // [중요] 전역 정규식(g flag) 재사용 시 lastIndex 초기화 필수
  MENTION_PATTERN.lastIndex = 0;

  while ((match = MENTION_PATTERN.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(
        <span key={`text-${lastIndex}`}>
          {text.slice(lastIndex, match.index)}
        </span>
      );
    }

    const mentionedName = match[1];

    parts.push(
      <Badge
        key={`mention-${match.index}`}
        colorPalette="primary"
        shape="square"
        size="sm"
        className="mx-0.5 align-middle"
      >
        @{mentionedName}
      </Badge>
    );

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    parts.push(
      <span key={`text-${lastIndex}`}>
        {text.slice(lastIndex)}
      </span>
    );
  }

  return parts;
};


const MessageContent = ({ content }) => {
  // 전체 렌더링 결과 메모이제이션
  const renderedContent = useMemo(() => {
    if (typeof content !== 'string') {
      return String(content);
    }

    const lines = content.split('\n');

    return lines.map((line, index) => (
      <React.Fragment key={index}>
        {parseTextWithMentions(line)}
        {index < lines.length - 1 && <br />}
      </React.Fragment>
    ));
  }, [content]);

  return (
    <Text
      typography="body2"
      className="message-text break-words"
      style={{ whiteSpace: 'pre-wrap' }}
    >
      {renderedContent}
    </Text>
  );
};

export default React.memo(MessageContent);