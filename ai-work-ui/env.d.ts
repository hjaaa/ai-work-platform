/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL: string
  readonly VITE_CLIENT_ID: string
  readonly VITE_CLIENT_SECRET: string
  readonly VITE_ENC_KEY: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
