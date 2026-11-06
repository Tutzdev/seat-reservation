/**
 * Every error from the API is RFC 9457 Problem Details with a stable `code`, so the interface branches on the
 * code and shows the detail the server wrote, instead of guessing from status numbers.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly body: Record<string, unknown>
  readonly retryAfterSeconds: number | null

  constructor(status: number, body: Record<string, unknown>, retryAfter: string | null) {
    super(typeof body.detail === 'string' ? body.detail : `The server answered ${status}.`)
    this.status = status
    this.code = typeof body.code === 'string' ? body.code : 'UNKNOWN'
    this.body = body
    this.retryAfterSeconds = retryAfter ? Number(retryAfter) : null
  }

  get fieldErrors(): { field: string; message: string }[] {
    return Array.isArray(this.body.errors) ? (this.body.errors as { field: string; message: string }[]) : []
  }
}

type TokenSource = () => string | null

let currentToken: TokenSource = () => null
let onExpired: () => void = () => {}

/** Wired once by the session provider: where the token comes from and what to do when it stops working. */
export function connectSession(token: TokenSource, expired: () => void) {
  currentToken = token
  onExpired = expired
}

export interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  headers?: Record<string, string>
  /** Another customer's token, for the crowd simulator; the session's token otherwise. */
  token?: string | null
  signal?: AbortSignal
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = options.token === undefined ? currentToken() : options.token
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(path, {
    method: options.method ?? (options.body === undefined ? 'GET' : 'POST'),
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  })

  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    if (response.status === 401 && token && options.token === undefined) {
      onExpired()
    }
    throw new ApiError(response.status, body, response.headers.get('Retry-After'))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

/** Safe to repeat: the server answers a retry with the same key with the request it already handled. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
