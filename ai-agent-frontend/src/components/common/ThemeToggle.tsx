import {useEffect, useState,} from "react";

type ThemeMode =
    | "system"
    | "light"
    | "dark";

const THEME_STORAGE_KEY =
    "ai-agent-theme";

function getSavedTheme(): ThemeMode {
    const savedTheme =
        localStorage.getItem(
            THEME_STORAGE_KEY,
        );

    if (
        savedTheme === "light"
        || savedTheme === "dark"
        || savedTheme === "system"
    ) {
        return savedTheme;
    }

    return "system";
}

function getSystemTheme():
    "light" | "dark" {
    return window.matchMedia(
        "(prefers-color-scheme: dark)",
    ).matches
        ? "dark"
        : "light";
}

function applyTheme(
    theme: ThemeMode,
): void {
    const resolvedTheme =
        theme === "system"
            ? getSystemTheme()
            : theme;

    document.documentElement.dataset.theme =
        resolvedTheme;
}

function ThemeToggle() {
    const [theme, setTheme] =
        useState<ThemeMode>(
            getSavedTheme,
        );

    useEffect(() => {
        applyTheme(
            theme,
        );

        localStorage.setItem(
            THEME_STORAGE_KEY,
            theme,
        );

        if (theme !== "system") {
            return;
        }

        const mediaQuery =
            window.matchMedia(
                "(prefers-color-scheme: dark)",
            );

        const handleSystemThemeChange =
            (): void => {
                applyTheme(
                    "system",
                );
            };

        mediaQuery.addEventListener(
            "change",
            handleSystemThemeChange,
        );

        return () => {
            mediaQuery.removeEventListener(
                "change",
                handleSystemThemeChange,
            );
        };
    }, [
        theme,
    ]);

    const handleThemeChange = (
        nextTheme: ThemeMode,
    ): void => {
        setTheme(
            nextTheme,
        );
    };

    return (
        <div className="theme-setting">
            <div className="theme-setting-label">
                Appearance
            </div>

            <div className="theme-toggle">
                <button
                    type="button"
                    className={
                        theme === "system"
                            ? "theme-toggle-button active"
                            : "theme-toggle-button"
                    }
                    onClick={() =>
                        handleThemeChange(
                            "system",
                        )
                    }
                >
                    System
                </button>

                <button
                    type="button"
                    className={
                        theme === "light"
                            ? "theme-toggle-button active"
                            : "theme-toggle-button"
                    }
                    onClick={() =>
                        handleThemeChange(
                            "light",
                        )
                    }
                >
                    Light
                </button>

                <button
                    type="button"
                    className={
                        theme === "dark"
                            ? "theme-toggle-button active"
                            : "theme-toggle-button"
                    }
                    onClick={() =>
                        handleThemeChange(
                            "dark",
                        )
                    }
                >
                    Dark
                </button>
            </div>
        </div>
    );
}

export default ThemeToggle;