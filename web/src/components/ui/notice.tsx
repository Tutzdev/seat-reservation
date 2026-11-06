import { CircleAlert, CircleCheck, Info } from 'lucide-react'
import type { ReactNode } from 'react'

type Tone = 'alert' | 'go' | 'info'

const tones: Record<Tone, { bar: string; icon: ReactNode }> = {
  alert: { bar: 'border-alert', icon: <CircleAlert aria-hidden className="mt-0.5 size-5 shrink-0 text-alert" /> },
  go: { bar: 'border-go', icon: <CircleCheck aria-hidden className="mt-0.5 size-5 shrink-0 text-go" /> },
  info: { bar: 'border-ink-soft', icon: <Info aria-hidden className="mt-0.5 size-5 shrink-0 text-ink-soft" /> },
}

/** A line of feedback with a colored edge. Alerts are announced to screen readers as they appear. */
export function Notice({ tone = 'info', children }: { tone?: Tone; children: ReactNode }) {
  const { bar, icon } = tones[tone]
  return (
    <div
      role={tone === 'alert' ? 'alert' : 'status'}
      className={`flex gap-3 border-l-4 bg-paper-raised px-4 py-3 text-base text-ink ${bar}`}
    >
      {icon}
      <div className="min-w-0">{children}</div>
    </div>
  )
}
