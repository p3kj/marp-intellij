import { describe, expect, it, vi } from 'vitest'
import { createHostChannel } from '../src/host'

describe('host channel', () => {
  it('queues until __marpHost exists, then flushes in order via __marpHostReady', () => {
    const win: any = {}
    const host = createHostChannel(win)
    host.post({ type: 'ready' })
    host.post({ type: 'revealLine', line: 3 })

    const post = vi.fn()
    win.__marpHost = { post }
    expect(post).not.toHaveBeenCalled()
    win.__marpHostReady()

    expect(post.mock.calls.map((c) => JSON.parse(c[0]))).toEqual([{ type: 'ready' }, { type: 'revealLine', line: 3 }])
  })

  it('sends immediately once the host exists and never twice', () => {
    const post = vi.fn()
    const win: any = { __marpHost: { post } }
    const host = createHostChannel(win)
    host.post({ type: 'didClick', line: 7 })
    win.__marpHostReady()
    expect(post).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledWith('{"type":"didClick","line":7}')
  })
})
