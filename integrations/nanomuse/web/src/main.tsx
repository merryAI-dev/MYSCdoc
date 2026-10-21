import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { markDesktopShell } from "./desktop";
import { applyLang } from "./i18n";
import { StoreProvider } from "./store";
import { applyTheme } from "./theme";
import "./index.css";

applyLang();
applyTheme();
markDesktopShell();
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <StoreProvider>
      <App />
    </StoreProvider>
  </React.StrictMode>,
);
