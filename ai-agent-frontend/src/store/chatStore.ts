import { create } from "zustand";

import type { ChatMessage } from "../types/chat";

interface ChatStore {

    messages: ChatMessage[];

    addMessage: (message: ChatMessage) => void;

    clearMessages: () => void;
}

export const useChatStore = create<ChatStore>((set) => ({

    messages: [],

    addMessage: (message) =>

        set((state) => ({

            messages: [...state.messages, message],

        })),

    clearMessages: () =>

        set({

            messages: [],

        }),

}));