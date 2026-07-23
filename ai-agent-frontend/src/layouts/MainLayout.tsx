import {NavLink, Outlet} from "react-router-dom";

import ChatRoomList from "../components/chat/ChatRoomList";

function MainLayout() {
    return (
        <div className="app-layout">
            <aside className="sidebar">
                <div className="sidebar-header">
                    <h1>AI Agent</h1>
                </div>

                <nav className="sidebar-menu">
                    <NavLink
                        to="/"
                        className={({isActive}) =>
                            isActive
                                ? "menu-item active"
                                : "menu-item"
                        }
                    >
                        채팅
                    </NavLink>

                    <div className="sidebar-chat-room-area">
                        <ChatRoomList />
                    </div>

                    <NavLink
                        to="/settings"
                        className={({isActive}) =>
                            isActive
                                ? "menu-item active"
                                : "menu-item"
                        }
                    >
                        설정
                    </NavLink>
                </nav>
            </aside>

            <main className="main-content">
                <Outlet />
            </main>
        </div>
    );
}

export default MainLayout;