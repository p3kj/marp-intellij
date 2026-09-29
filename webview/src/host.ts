import type { HostMessage } from './types'

interface HostWindow {
  __marpHost?: { post(msg: string): void }
  __marpHostReady?: () => void
}

/** Sends JSON messages to Kotlin, queueing them until `window.__marpHost` is injected. */
export function createHostChannel(win: HostWindow) {
  const queue: string[] = []

  const flush = () => {
    const host = win.__marpHost
    if (!host) return
    while (queue.length) host.post(queue.shift()!)
  }

  win.__marpHostReady = flush

  return {
    post(msg: HostMessage) {
      queue.push(JSON.stringify(msg))
      flush()
    },
    flush,
  }
}
