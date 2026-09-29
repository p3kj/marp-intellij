import type { MarpBridge } from './types'

declare global {
  interface Window {
    marpBridge: MarpBridge
    /** Injected by Kotlin on every main-frame load end. */
    __marpHost?: { post(msg: string): void }
    __marpHostReady?: () => void
  }
}
