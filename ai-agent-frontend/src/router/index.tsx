import { createBrowserRouter } from "react-router-dom";

import MainLayout from "../layouts/MainLayout";
import ChatPage from "../pages/Chat/ChatPage";
import LoginPage from "../pages/Login/LoginPage";
import SettingsPage from "../pages/Settings/SettingsPage";
import NotFoundPage from "../pages/NotFound/NotFoundPage";

const router = createBrowserRouter([
    {
        path: "/",
        element: <MainLayout />,
        children: [
            {
                index: true,
                element: <ChatPage />,
            },
            {
                path: "settings",
                element: <SettingsPage />,
            },
        ],
    },
    {
        path: "/login",
        element: <LoginPage />,
    },
    {
        path: "*",
        element: <NotFoundPage />,
    },
]);

export default router;