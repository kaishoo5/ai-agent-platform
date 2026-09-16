import {NavLink, Outlet,} from "react-router-dom";

import ChatRoomList from "../components/chat/ChatRoomList";
import ThemeToggle from "../components/common/ThemeToggle";

function MainLayout() {
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

            <main className="main-content">
                <Outlet />
            </main>
        </div>
    );
}

export default MainLayout;