package com.heikeji.phonesearch.ui.chat

import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import org.junit.Assert.*
import org.junit.Test

class ChatBubbleTest {
    @Test fun `text bubbles compare by content without images`() {
        val bubble = ChatBubble(ChatRole.ASSISTANT, "answer")
        assertEquals(bubble, bubble.copy())
        assertNotEquals(bubble, bubble.copy(text = "updated"))
    }

    @Test fun `image bubbles compare image content`() {
        val bubble = ChatBubble(ChatRole.USER, "", imageBytes = byteArrayOf(1, 2))
        assertEquals(bubble, bubble.copy(imageBytes = byteArrayOf(1, 2)))
        assertNotEquals(bubble, bubble.copy(imageBytes = byteArrayOf(2, 1)))
        assertNotEquals(bubble, bubble.copy(imageBytes = null))
    }
}
