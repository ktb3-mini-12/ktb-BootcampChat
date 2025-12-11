import React, { useCallback, useEffect, useRef, useState, forwardRef, useMemo, memo } from "react";
import dynamic from "next/dynamic";
import { LikeIcon, AttachFileOutlineIcon, SendIcon } from "@vapor-ui/icons";
import { Button, IconButton, VStack, HStack, Box, Field, Textarea } from "@vapor-ui/core";

// emoji-mart 동적 import (~500KB 번들 사이즈 감소)
const EmojiPicker = dynamic(() => import("./EmojiPicker"), {
  loading: () => <Box padding="$200">이모지 로딩중...</Box>,
  ssr: false,
});
import MentionDropdown from "./MentionDropdown";
import FilePreview from "./FilePreview";
import fileService from "@/services/fileService";

const ChatInput = forwardRef(
  (
    {
      message = "",
      onMessageChange = () => {},
      onSubmit = () => {},
      onEmojiToggle = () => {},
      onFileSelect = () => {},
      fileInputRef,
      disabled = false,
      uploading: externalUploading = false,
      showEmojiPicker = false,
      showMentionList = false,
      mentionFilter = "",
      mentionIndex = 0,
      getFilteredParticipants = () => [],
      setMessage = () => {},
      setShowEmojiPicker = () => {},
      setShowMentionList = () => {},
      setMentionFilter = () => {},
      setMentionIndex = () => {},
      room = null,
    },
    ref
  ) => {
    const emojiPickerRef = useRef(null);
    const emojiButtonRef = useRef(null);
    const dropZoneRef = useRef(null);
    const internalInputRef = useRef(null);
    const messageInputRef = ref || internalInputRef;
    const [files, setFiles] = useState([]);
    const [uploading, setUploading] = useState(false);
    const [uploadProgress, setUploadProgress] = useState(0);
    const [uploadError, setUploadError] = useState(null);
    const [isDragging, setIsDragging] = useState(false);
    const [mentionPosition, setMentionPosition] = useState({ top: 0, left: 0 });
    const mentionDebounceRef = useRef(null);
    const measureDivRef = useRef(null);
    const cachedStylesRef = useRef(null);

    const handleFileValidationAndPreview = useCallback(
      async (file) => {
        if (!file) return;

        try {
          await fileService.validateFile(file);

          const filePreview = {
            file,
            url: URL.createObjectURL(file),
            name: file.name,
            type: file.type,
            size: file.size,
          };

          setFiles((prev) => [...prev, filePreview]);
          setUploadError(null);
          onFileSelect?.(file);
        } catch (error) {
          console.error("File validation error:", error);
          setUploadError(error.message);
        } finally {
          if (fileInputRef?.current) {
            fileInputRef.current.value = "";
          }
        }
      },
      [onFileSelect]
    );

    const handleFileRemove = useCallback((fileToRemove) => {
      setFiles((prev) => prev.filter((file) => file.name !== fileToRemove.name));
      URL.revokeObjectURL(fileToRemove.url);
      setUploadError(null);
      setUploadProgress(0);
    }, []);

    const handleFileDrop = useCallback(
      async (e) => {
        e.preventDefault();
        e.stopPropagation();
        setIsDragging(false);

        const droppedFiles = Array.from(e.dataTransfer.files);
        if (droppedFiles.length === 0) return;

        try {
          await handleFileValidationAndPreview(droppedFiles[0]);
        } catch (error) {
          console.error("File drop error:", error);
        }
      },
      [handleFileValidationAndPreview]
    );

    const handleSubmit = useCallback(
      async (e) => {
        e?.preventDefault();

        if (files.length > 0) {
          try {
            const file = files[0];
            if (!file || !file.file) {
              throw new Error("파일이 선택되지 않았습니다.");
            }

            onSubmit({
              type: "file",
              content: message.trim(),
              fileData: file,
            });

            setMessage("");
            setFiles([]);
          } catch (error) {
            console.error("File submit error:", error);
            setUploadError(error.message);
          }
        } else if (message.trim()) {
          onSubmit({
            type: "text",
            content: message.trim(),
          });
          setMessage("");
        }
      },
      [files, message, onSubmit, setMessage]
    );

    useEffect(() => {
      const handleClickOutside = (event) => {
        if (
          showEmojiPicker &&
          !emojiPickerRef.current?.contains(event.target) &&
          !emojiButtonRef.current?.contains(event.target)
        ) {
          setShowEmojiPicker(false);
        }
      };

      const handlePaste = async (event) => {
        if (!messageInputRef?.current?.contains(event.target)) return;

        const items = event.clipboardData?.items;
        if (!items) return;

        const fileItem = Array.from(items).find(
          (item) =>
            item.kind === "file" &&
            (item.type.startsWith("image/") ||
              item.type.startsWith("video/") ||
              item.type.startsWith("audio/") ||
              item.type === "application/pdf")
        );

        if (!fileItem) return;

        const file = fileItem.getAsFile();
        if (!file) return;

        try {
          await handleFileValidationAndPreview(file);
          event.preventDefault();
        } catch (error) {
          console.error("File paste error:", error);
        }
      };

      document.addEventListener("mousedown", handleClickOutside);
      document.addEventListener("paste", handlePaste);

      return () => {
        document.removeEventListener("mousedown", handleClickOutside);
        document.removeEventListener("paste", handlePaste);
        files.forEach((file) => URL.revokeObjectURL(file.url));
        if (mentionDebounceRef.current) {
          clearTimeout(mentionDebounceRef.current);
        }
        // measureDiv cleanup
        if (measureDivRef.current && measureDivRef.current.parentNode) {
          measureDivRef.current.parentNode.removeChild(measureDivRef.current);
          measureDivRef.current = null;
        }
        cachedStylesRef.current = null;
      };
    }, [showEmojiPicker, setShowEmojiPicker, files, messageInputRef, handleFileValidationAndPreview]);

    const calculateMentionPosition = useCallback((textarea, atIndex) => {
      const textBeforeAt = textarea.value.slice(0, atIndex);
      const lines = textBeforeAt.split("\n");
      const currentLineIndex = lines.length - 1;
      const currentLineText = lines[currentLineIndex];

      // measureDiv 재사용 (DOM 조작 비용 감소)
      if (!measureDivRef.current) {
        measureDivRef.current = document.createElement("div");
        measureDivRef.current.style.position = "absolute";
        measureDivRef.current.style.visibility = "hidden";
        measureDivRef.current.style.whiteSpace = "pre";
        measureDivRef.current.style.pointerEvents = "none";
        document.body.appendChild(measureDivRef.current);
      }

      // textarea 스타일 캐싱 (getComputedStyle 호출 최소화)
      const computedStyle = window.getComputedStyle(textarea);
      if (!cachedStylesRef.current || cachedStylesRef.current.element !== textarea) {
        cachedStylesRef.current = {
          element: textarea,
          font: computedStyle.font,
          fontSize: computedStyle.fontSize,
          fontFamily: computedStyle.fontFamily,
          fontWeight: computedStyle.fontWeight,
          letterSpacing: computedStyle.letterSpacing,
          textTransform: computedStyle.textTransform,
          paddingLeft: parseInt(computedStyle.paddingLeft),
          paddingTop: parseInt(computedStyle.paddingTop),
          lineHeight: parseInt(computedStyle.lineHeight) || parseFloat(computedStyle.fontSize) * 1.5,
        };

        // measureDiv에 캐싱된 스타일 적용
        const div = measureDivRef.current;
        div.style.font = cachedStylesRef.current.font;
        div.style.fontSize = cachedStylesRef.current.fontSize;
        div.style.fontFamily = cachedStylesRef.current.fontFamily;
        div.style.fontWeight = cachedStylesRef.current.fontWeight;
        div.style.letterSpacing = cachedStylesRef.current.letterSpacing;
        div.style.textTransform = cachedStylesRef.current.textTransform;
      }

      measureDivRef.current.textContent = currentLineText;
      const textWidth = measureDivRef.current.offsetWidth;

      const textareaRect = textarea.getBoundingClientRect();
      const { paddingLeft, paddingTop, lineHeight } = cachedStylesRef.current;
      const scrollTop = textarea.scrollTop;

      let left = textareaRect.left + paddingLeft + textWidth;
      let top = textareaRect.top + paddingTop + currentLineIndex * lineHeight - scrollTop;

      const dropdownWidth = 320;
      const dropdownHeight = 250;
      const viewportWidth = window.innerWidth;

      if (left + dropdownWidth > viewportWidth) {
        left = viewportWidth - dropdownWidth - 10;
      }
      if (left < 10) {
        left = 10;
      }

      top = top + 40;

      if (top - dropdownHeight < 10) {
        top = textareaRect.top + paddingTop + (currentLineIndex + 1) * lineHeight - scrollTop + 2;
      } else {
        top = top - dropdownHeight;
      }

      return { top, left };
    }, []);

    const handleInputChange = useCallback(
      (e) => {
        const value = e.target.value;
        const cursorPosition = e.target.selectionStart;
        const textBeforeCursor = value.slice(0, cursorPosition);
        const lastAtSymbol = textBeforeCursor.lastIndexOf("@");
        const textarea = e.target;

        // 입력값은 즉시 반영
        onMessageChange(e);

        // 이전 디바운스 타이머 취소
        if (mentionDebounceRef.current) {
          clearTimeout(mentionDebounceRef.current);
        }

        if (lastAtSymbol !== -1) {
          const textAfterAt = textBeforeCursor.slice(lastAtSymbol + 1);
          const hasSpaceAfterAt = textAfterAt.includes(" ");

          if (!hasSpaceAfterAt) {
            // 멘션 필터는 즉시 업데이트 (드롭다운 내용 반영)
            setMentionFilter(textAfterAt.toLowerCase());
            setShowMentionList(true);
            setMentionIndex(0);

            // 멘션 위치 계산은 디바운싱 (50ms) - DOM 조작 비용 감소
            mentionDebounceRef.current = setTimeout(() => {
              const position = calculateMentionPosition(textarea, lastAtSymbol);
              setMentionPosition(position);
            }, 50);
            return;
          }
        }

        setShowMentionList(false);
      },
      [onMessageChange, setMentionFilter, setShowMentionList, setMentionIndex, calculateMentionPosition]
    );

    const handleMentionSelect = useCallback(
      (user) => {
        if (!messageInputRef?.current) return;

        const cursorPosition = messageInputRef.current.selectionStart;
        const textBeforeCursor = message.slice(0, cursorPosition);
        const textAfterCursor = message.slice(cursorPosition);
        const lastAtSymbol = textBeforeCursor.lastIndexOf("@");

        if (lastAtSymbol !== -1) {
          const newMessage = message.slice(0, lastAtSymbol) + `@${user.name} ` + textAfterCursor;

          setMessage(newMessage);
          setShowMentionList(false);

          setTimeout(() => {
            if (messageInputRef.current) {
              const newPosition = lastAtSymbol + user.name.length + 2;
              messageInputRef.current.focus();
              messageInputRef.current.setSelectionRange(newPosition, newPosition);
            }
          }, 0);
        }
      },
      [message, setMessage, setShowMentionList, messageInputRef]
    );

    const handleKeyDown = useCallback(
      (e) => {
        if (showMentionList) {
          const participants = getFilteredParticipants(room);
          const participantsCount = participants.length;

          switch (e.key) {
            case "ArrowDown":
              e.preventDefault();
              setMentionIndex((prev) => (prev < participantsCount - 1 ? prev + 1 : 0));
              break;

            case "ArrowUp":
              e.preventDefault();
              setMentionIndex((prev) => (prev > 0 ? prev - 1 : participantsCount - 1));
              break;

            case "Tab":
            case "Enter":
              e.preventDefault();
              if (participantsCount > 0) {
                handleMentionSelect(participants[mentionIndex]);
              }
              break;

            case "Escape":
              e.preventDefault();
              setShowMentionList(false);
              break;

            default:
              return;
          }
        } else if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
          e.preventDefault();
          if (message.trim() || files.length > 0) {
            handleSubmit(e);
          }
        } else if (e.key === "Escape" && showEmojiPicker) {
          setShowEmojiPicker(false);
        }
      },
      [
        message,
        files,
        showMentionList,
        showEmojiPicker,
        mentionIndex,
        getFilteredParticipants,
        handleMentionSelect,
        handleSubmit,
        setMentionIndex,
        setShowMentionList,
        setShowEmojiPicker,
        room,
      ]
    );

    const handleEmojiSelect = useCallback(
      (emoji) => {
        if (!messageInputRef?.current) return;

        const cursorPosition = messageInputRef.current.selectionStart || message.length;
        const newMessage = message.slice(0, cursorPosition) + emoji.native + message.slice(cursorPosition);

        setMessage(newMessage);
        setShowEmojiPicker(false);

        setTimeout(() => {
          if (messageInputRef.current) {
            const newCursorPosition = cursorPosition + emoji.native.length;
            messageInputRef.current.focus();
            messageInputRef.current.setSelectionRange(newCursorPosition, newCursorPosition);
          }
        }, 0);
      },
      [message, setMessage, setShowEmojiPicker, messageInputRef]
    );

    const toggleEmojiPicker = useCallback(() => {
      setShowEmojiPicker((prev) => !prev);
    }, [setShowEmojiPicker]);

    const isDisabled = disabled || uploading || externalUploading;

    return (
      <>
        <Box
          ref={dropZoneRef}
          className="relative"
          padding="$200 $400"
          onDragEnter={(e) => {
            e.preventDefault();
            e.stopPropagation();
            setIsDragging(true);
          }}
          onDragLeave={(e) => {
            e.preventDefault();
            e.stopPropagation();
            setIsDragging(false);
          }}
          onDragOver={(e) => {
            e.preventDefault();
            e.stopPropagation();
            setIsDragging(true);
          }}
          onDrop={handleFileDrop}
        >
          {files.length > 0 && (
            <Box className="absolute bottom-full left-0 right-0 mb-2 z-1000">
              <FilePreview
                files={files}
                uploading={uploading}
                uploadProgress={uploadProgress}
                uploadError={uploadError}
                onRemove={handleFileRemove}
                onRetry={() => setUploadError(null)}
                showFileName={true}
                showFileSize={true}
                variant="default"
              />
            </Box>
          )}

          <VStack gap="$100" width="100%">
            <VStack gap="$025" className="relative">
              <HStack gap="$200" alignItems="flex-end">
                <Textarea
                  ref={messageInputRef}
                  value={message}
                  onChange={handleInputChange}
                  onKeyDown={handleKeyDown}
                  placeholder={
                    isDragging
                      ? "파일을 여기에 놓아주세요."
                      : "메시지를 입력하세요... (@를 입력하여 멘션, Shift + Enter로 줄바꿈)"
                  }
                  disabled={isDisabled}
                  rows={1}
                  autoComplete="off"
                  spellCheck="true"
                  size="xl"
                  autoResize={true}
                  data-testid="chat-message-input"
                />

                <IconButton
                  key="chat-send-btn"
                  size="xl"
                  onClick={handleSubmit}
                  disabled={isDisabled || (!message.trim() && files.length === 0)}
                  aria-label="메시지 보내기"
                  data-testid="chat-send-button"
                >
                  <SendIcon />
                </IconButton>
              </HStack>

              <HStack gap="$100">
                <IconButton
                  ref={emojiButtonRef}
                  colorPalette="contrast"
                  variant="ghost"
                  size="md"
                  onClick={toggleEmojiPicker}
                  disabled={isDisabled}
                  aria-label="이모티콘"
                >
                  <LikeIcon />
                </IconButton>
                <IconButton
                  colorPalette="contrast"
                  variant="ghost"
                  size="md"
                  onClick={() => fileInputRef?.current?.click()}
                  disabled={isDisabled}
                  aria-label="파일 첨부"
                >
                  <AttachFileOutlineIcon />
                </IconButton>
              </HStack>

              <input
                type="file"
                ref={fileInputRef}
                onChange={(e) => handleFileValidationAndPreview(e.target.files?.[0])}
                style={{ display: "none" }}
                accept="image/*,application/pdf"
                data-testid="file-upload-input"
              />

              {showEmojiPicker && (
                <Box
                  ref={emojiPickerRef}
                  onClick={(e) => e.stopPropagation()}
                  className="absolute bottom-full left-0 z-1000"
                >
                  <EmojiPicker
                    onSelect={handleEmojiSelect}
                    emojiSize={20}
                    emojiButtonSize={36}
                    perLine={8}
                    maxFrequentRows={4}
                  />
                </Box>
              )}
            </VStack>
          </VStack>
        </Box>

        {showMentionList && (
          <Box
            className="fixed z-9999"
            style={{
              top: `${mentionPosition.top}px`,
              left: `${mentionPosition.left}px`,
            }}
          >
            <MentionDropdown
              participants={getFilteredParticipants(room)}
              activeIndex={mentionIndex}
              onSelect={handleMentionSelect}
              onMouseEnter={(index) => setMentionIndex(index)}
            />
          </Box>
        )}
      </>
    );
  }
);

ChatInput.displayName = "ChatInput";

export default memo(ChatInput);
