/** Decode a stream of server-sent events, independent of the response transport. */
export async function readEvents<T>(stream: ReadableStream<BufferSource>, onProgress: (status: T) => void) {
  const reader = stream.pipeThrough(new TextDecoderStream()).getReader()
  let pending = ''
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      pending += value
      let end = pending.indexOf('\n\n')
      while (end >= 0) {
        const frame = pending.slice(0, end)
        if (frame.length > 65536) throw new Error('Progress event is too large')
        pending = pending.slice(end + 2)
        const data = frame.split('\n').find(line => line.startsWith('data: '))
        if (data) onProgress(JSON.parse(data.slice(6)) as T)
        end = pending.indexOf('\n\n')
      }
      if (pending.length > 65536) throw new Error('Progress event is too large')
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export function demoEvents(signal: AbortSignal) {
  return new ReadableStream<BufferSource>({
    start(controller) {
      const encoder = new TextEncoder()
      const interval = window.setInterval(() => {
        controller.enqueue(encoder.encode(`event: progress\ndata: ${JSON.stringify({ tick: Date.now() })}\n\n`))
      }, 2400)
      signal.addEventListener('abort', () => {
        window.clearInterval(interval)
        controller.close()
      }, { once: true })
    },
  })
}
