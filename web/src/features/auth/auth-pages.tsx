import { zodResolver } from '@hookform/resolvers/zod'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Notice } from '@/components/ui/notice'
import { useSession } from '@/features/auth/session'
import { ApiError } from '@/lib/api'
import { formatClock } from '@/lib/format'

const signInSchema = z.object({
  email: z.email('Enter the e-mail you signed up with.'),
  password: z.string().min(1, 'Enter your password.'),
})

const signUpSchema = z.object({
  name: z.string().trim().min(1, 'Enter your name as it should appear on the tickets.').max(120),
  email: z.email('Enter an e-mail address like ana@mail.com.'),
  password: z.string().min(12, 'Use at least 12 characters.').max(128),
})

function useNext(): string {
  const [params] = useSearchParams()
  const next = params.get('next')
  return next && next.startsWith('/') ? next : '/'
}

function describe(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'TOO_MANY_LOGIN_ATTEMPTS' && error.retryAfterSeconds) {
      return `Too many failed attempts for this e-mail. Try again in ${formatClock(error.retryAfterSeconds * 1000)}.`
    }
    return error.message
  }
  return 'The server could not be reached. Check your connection and try again.'
}

function AuthLayout({ title, intro, children }: { title: string; intro: string; children: ReactNode }) {
  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-5xl font-extrabold">{title}</h1>
      <p className="mt-3 text-lg text-ink-soft">{intro}</p>
      <div className="mt-8">{children}</div>
    </div>
  )
}

export function SignInPage() {
  const { session, signIn } = useSession()
  const navigate = useNavigate()
  const next = useNext()
  const [failure, setFailure] = useState<string | null>(null)
  const form = useForm<z.infer<typeof signInSchema>>({ resolver: zodResolver(signInSchema) })

  if (session) return <Navigate to={next} replace />

  const submit = form.handleSubmit(async ({ email, password }) => {
    setFailure(null)
    try {
      await signIn(email, password)
      navigate(next, { replace: true })
    } catch (error) {
      setFailure(describe(error))
    }
  })

  const demoEmail = import.meta.env.VITE_DEMO_ORGANIZER_EMAIL as string | undefined
  return (
    <AuthLayout title="Sign in" intro="Your place in line and your seats are tied to your account.">
      <form onSubmit={submit} noValidate className="flex flex-col gap-5">
        {failure && <Notice tone="alert">{failure}</Notice>}
        <Field label="E-mail" type="email" autoComplete="email" {...form.register('email')}
          error={form.formState.errors.email?.message} />
        <Field label="Password" type="password" autoComplete="current-password" {...form.register('password')}
          error={form.formState.errors.password?.message} />
        <Button type="submit" busy={form.formState.isSubmitting}>Sign in</Button>
      </form>
      <p className="mt-6 text-base text-ink-soft">
        New here?{' '}
        <Link to={`/sign-up?next=${encodeURIComponent(next)}`} className="font-semibold text-velvet underline underline-offset-2">
          Create an account
        </Link>
      </p>
      {demoEmail && (
        <p className="mt-8 border-t border-line pt-4 text-sm text-ink-soft">
          Demo organizer: {demoEmail} / {import.meta.env.VITE_DEMO_ORGANIZER_PASSWORD as string}
        </p>
      )}
    </AuthLayout>
  )
}

export function SignUpPage() {
  const { session, signUp } = useSession()
  const navigate = useNavigate()
  const next = useNext()
  const [failure, setFailure] = useState<string | null>(null)
  const form = useForm<z.infer<typeof signUpSchema>>({ resolver: zodResolver(signUpSchema) })

  if (session) return <Navigate to={next} replace />

  const submit = form.handleSubmit(async ({ name, email, password }) => {
    setFailure(null)
    try {
      await signUp(name, email, password)
      navigate(next, { replace: true })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'EMAIL_ALREADY_REGISTERED') {
        form.setError('email', { message: 'This e-mail already has an account. Sign in instead.' })
        return
      }
      setFailure(describe(error))
    }
  })

  return (
    <AuthLayout title="Create an account" intro="It takes a minute, and you will need it to hold seats.">
      <form onSubmit={submit} noValidate className="flex flex-col gap-5">
        {failure && <Notice tone="alert">{failure}</Notice>}
        <Field label="Name" autoComplete="name" {...form.register('name')} error={form.formState.errors.name?.message} />
        <Field label="E-mail" type="email" autoComplete="email" {...form.register('email')}
          error={form.formState.errors.email?.message} />
        <Field label="Password" type="password" autoComplete="new-password" hint="At least 12 characters."
          {...form.register('password')} error={form.formState.errors.password?.message} />
        <Button type="submit" busy={form.formState.isSubmitting}>Create account</Button>
      </form>
      <p className="mt-6 text-base text-ink-soft">
        Already have one?{' '}
        <Link to={`/sign-in?next=${encodeURIComponent(next)}`} className="font-semibold text-velvet underline underline-offset-2">
          Sign in
        </Link>
      </p>
    </AuthLayout>
  )
}
