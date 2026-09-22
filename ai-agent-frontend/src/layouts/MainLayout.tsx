import {useEffect, useState,} from "react";
import {NavLink, Outlet, useLocation,} from "react-router-dom";

import ChatRoomList from "../components/chat/ChatRoomList";
import ThemeToggle from "../components/common/ThemeToggle";

function MainLayout() {
    const location = useLocation();

    const [chatDrawerPathname, setChatDrawerPathname] =
        useState<string | null>(null);

    const isChatDrawerOpen =
        chatDrawerPathname === location.pathname;

    useEffect(() => {
        if (!isChatDrawerOpen) {
            return;
        }

        const handleKeyDown = (
            event: KeyboardEvent,
        ): void => {
            if (event.key === "Escape") {
                setChatDrawerPathname(null);
            }
        };

        window.addEventListener(
            "keydown",
            handleKeyDown,
        );

        return () => {
            window.removeEventListener(
                "keydown",
                handleKeyDown,
            );
        };
    }, [isChatDrawerOpen]);

    const handleChatNavClick = (): void => {
        if (window.innerWidth <= 640) {
            setChatDrawerPathname(
                (current) =>
                    current === location.pathname
                        ? null
                        : location.pathname,
            );
        }
    };

    const handleCloseChatDrawer = (): void => {
        setChatDrawerPathname(null);
    };

    return (
        <div className="app-layout">
            <aside className="sidebar">
                <div className="sidebar-brand">
                    <div className="sidebar-brand-icon">
                        A
                    </div>

                    <div className="sidebar-brand-text">
                        <strong>
                            AI Agent
                        </strong>

                        <span>
                            Workspace
                        </span>
                    </div>
                </div>

                <div className="sidebar-content">
                    <NavLink
                        to="/"
                        className={({isActive}) =>
                            isActive
                                ? "sidebar-nav-item active"
                                : "sidebar-nav-item"
                        }
                        onClick={handleChatNavClick}
                    >
                        <span className="sidebar-nav-icon">
                            ◇
                        </span>

                        <span>
                            Chat
                        </span>
                    </NavLink>

                    <ChatRoomList />
                </div>

                <div className="sidebar-footer">
                    <NavLink
                        to="/settings"
                        className={({isActive}) =>
                            isActive
                                ? "sidebar-nav-item active"
                                : "sidebar-nav-item"
                        }
                    >
                        <span className="sidebar-nav-icon">
                            ⚙
                        </span>

                        <span>
                            Settings
                        </span>
                    </NavLink>

                    <ThemeToggle />

                    <div className="sidebar-status">
                        <span className="sidebar-status-dot" />

                        <div>
                            <strong>
                                Local Agent
                            </strong>

                            <span>
                                Ready
                            </span>
                        </div>
                    </div>
                </div>
            </aside>

            {isChatDrawerOpen && (
                <>
                    <button
                        type="button"
                        className="mobile-chat-drawer-backdrop"
                        aria-label="채팅 목록 닫기"
                        onClick={handleCloseChatDrawer}
                    />

                    <aside className="mobile-chat-drawer">
                        <div className="mobile-chat-drawer-header">
                            <div>
                                <strong>
                                    Chats
                                </strong>

                                <span>
                                    Recent conversations
                                </span>
                            </div>

                            <button
                                type="button"
                                className="mobile-chat-drawer-close"
                                aria-label="채팅 목록 닫기"
                                onClick={handleCloseChatDrawer}
                            >
                                ×
                            </button>
                        </div>

                        <ChatRoomList
                            onRoomSelected={
                                handleCloseChatDrawer
                            }
                        />

                        <div className="mobile-chat-drawer-theme">
                            <ThemeToggle />
                        </div>
                    </aside>
                </>
            )}

            <main className="main-content">
                <Outlet />
            </main>
        </div>
    );
}

export default MainLayout;