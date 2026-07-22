import {create} from "zustand";

import type {ChatMessage} from "../types/chat";

interface ChatStore {

    messages: ChatMessage[];

    addMessage: (message: ChatMessage) => void;

    appendMessageContent: (
        messageId: string,
        content: string,
    ) => void;

    updateMessageContent: (
        messageId: string,
        content: string,
    ) => void;

    clearMessages: () => void;

}

export const useChatStore = create<ChatStore>((set) => ({

    messages: [],

    addMessage: (message) => {
        set((state) => ({
            messages: [
                ...state.messages,
                message,
            ],
        }));
    },

    appendMessageContent: (
        messageId,
        content,
    ) => {
        set((state) => ({
            messages: state.messages.map((message) => {
                if (message.id !== messageId) {
                    return message;
                }

                return {
                    ...message,
                    content: message.content + content,
                };
            }),
        }));
    },

    updateMessageContent: (
        messageId,
        content,
    ) => {
        set((state) => ({
            messages: state.messages.map((message) => {
                if (message.id !== messageId) {
                    return message;
                }

                return {
                    ...message,
                    content,
                };
            }),
        }));
    },

    clearMessages: () => {
        set({
            messages: [],
        });
    },

}));