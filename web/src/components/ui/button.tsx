import { LoaderCircle } from 'lucide-react'
import type { ButtonHTMLAttributes } from 'react'

type Variant = 'primary' | 'quiet' | 'danger'

const variants: Record<Variant, string> = {
  primary: 'bg-velvet text-white hover:bg-velvet-deep disabled:bg-taken disabled:text-ink-soft',
  quiet: 'bg-transparent text-ink ring-1 ring-inset ring-line hover:bg-paper-raised hover:ring-ink-soft',
  danger: 'bg-transparent text-alert ring-1 ring-inset ring-alert/40 hover:bg-alert/5',
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  busy?: boolean
}

export function Button({ variant = 'primary', busy = false, disabled, className = '', children, ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-5 text-base font-semibold
        transition-colors disabled:cursor-not-allowed ${variants[variant]} ${className}`}
    >
      {busy && <LoaderCircle aria-hidden className="size-4 animate-spin" />}
      {children}
    </button>
  )
}
