import React, { useState, useEffect } from "react";
import { useRouter } from "next/router";
import { ErrorCircleIcon } from "@vapor-ui/icons";
import { Box, Button, Field, Form, HStack, Switch, Text, TextInput, VStack, Callout } from "@vapor-ui/core";
import { useAuth } from "@/contexts/AuthContext";

function NewChatRoom() {
  const router = useRouter();
  const { user: currentUser } = useAuth();
  const [formData, setFormData] = useState({
    name: "",
    hasPassword: false,
    password: "",
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  // 유효성 검사
  const nameLength = formData.name.trim().length;
  const isNameTooShort = nameLength > 0 && nameLength < 2;
  const isNameValid = nameLength >= 2;

  // [삭제됨] joinRoom 함수는 더 이상 여기서 필요하지 않습니다.
  // 방 생성 시 백엔드가 자동으로 유저를 참가시키기 때문입니다.

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (!isNameValid) {
      setError("채팅방 이름은 2글자 이상이어야 합니다.");
      return;
    }

    if (formData.hasPassword && !formData.password) {
      setError("비밀번호를 입력해주세요.");
      return;
    }

    if (!currentUser?.token) {
      setError("인증 정보가 없습니다. 다시 로그인해주세요.");
      return;
    }

    try {
      setLoading(true);
      setError("");

      // 1. 방 생성 요청
      const response = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/rooms`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-auth-token": currentUser.token,
          "x-session-id": currentUser.sessionId,
        },
        body: JSON.stringify({
          name: formData.name.trim(),
          password: formData.hasPassword ? formData.password : undefined,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();

        let errorMessage = "채팅방 생성에 실패했습니다.";
        if (errorData.errors && Array.isArray(errorData.errors) && errorData.errors.length > 0) {
          errorMessage = errorData.errors[0].message;
        } else if (errorData.message) {
          errorMessage = errorData.message;
        }
        if (response.status === 401) {
          errorMessage = "인증이 만료되었습니다. 다시 로그인해주세요.";
        }
        throw new Error(errorMessage);
      }

      const { data } = await response.json();

      // [핵심 수정]
      // await joinRoom(...) <--- 이 불필요한 요청을 삭제했습니다.
      // 방금 만든 방의 ID(data._id)를 가지고 바로 채팅방으로 이동합니다.
      router.push(`/chat/${data._id}`);
    } catch (error) {
      console.error("Room creation error:", error);
      setError(error.message);
    } finally {
      // 페이지 이동 중에는 로딩 상태를 풀지 않아도 됩니다 (UX상 더 자연스러움)
      // 에러가 났을 때만 로딩을 풉니다.
      if (error) {
        setLoading(false);
      }
    }
  };

  return (
    <Box display="flex" justifyContent="center" alignItems="center" minHeight="100vh" padding="$300">
      <VStack
        gap="$400"
        width="400px"
        padding="$400"
        borderRadius="$300"
        border="1px solid var(--vapor-color-border-normal)"
        backgroundColor="var(--vapor-color-surface-raised)"
        render={<Form onSubmit={handleSubmit} />}
      >
        <Text typography="heading4">새 채팅방</Text>

        {error && (
          <Callout color="danger">
            <HStack gap="$200" alignItems="center">
              <ErrorCircleIcon size={16} />
              <Text>{error}</Text>
            </HStack>
          </Callout>
        )}

        <VStack gap="$300" width="100%">
          <Field.Root>
            <Box render={<Field.Label />} flexDirection="column">
              <Text typography="subtitle2" foreground="normal-200">
                채팅방 이름
              </Text>
              <TextInput
                id="room-name"
                required
                size="lg"
                placeholder="채팅방 이름을 입력하세요 (2자 이상)"
                value={formData.name}
                invalid={isNameTooShort}
                onChange={(e) => {
                  setFormData((prev) => ({ ...prev, name: e.target.value }));
                  if (error) setError("");
                }}
                disabled={loading}
                data-testid="chat-room-name-input"
              />
            </Box>

            {isNameTooShort && (
              <Text typography="body3" color="var(--vapor-color-text-danger)">
                이름은 최소 2글자 이상이어야 합니다.
              </Text>
            )}
          </Field.Root>

          <Field.Root>
            <HStack width="100%" justifyContent="space-between" render={<Field.Label />}>
              비밀번호 설정
              <Switch.Root
                id="room-password-toggle"
                checked={formData.hasPassword}
                onCheckedChange={(checked) =>
                  setFormData((prev) => ({
                    ...prev,
                    hasPassword: checked,
                    password: checked ? prev.password : "",
                  }))
                }
                disabled={loading}
              />
            </HStack>
          </Field.Root>

          {formData.hasPassword && (
            <Field.Root>
              <Box render={<Field.Label />} flexDirection="column">
                <Text typography="subtitle2" foreground="normal-200">
                  비밀번호
                </Text>
                <TextInput
                  id="room-password"
                  type="password"
                  size="lg"
                  placeholder="비밀번호를 입력하세요"
                  value={formData.password}
                  onChange={(e) => setFormData((prev) => ({ ...prev, password: e.target.value }))}
                  disabled={loading}
                />
              </Box>
            </Field.Root>
          )}

          <Button
            type="submit"
            size="lg"
            disabled={loading || !isNameValid || (formData.hasPassword && !formData.password)}
            data-testid="create-chat-room-button"
          >
            {loading ? "생성 중..." : "채팅방 만들기"}
          </Button>
        </VStack>
      </VStack>
    </Box>
  );
}

export default NewChatRoom;
