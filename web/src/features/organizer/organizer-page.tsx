import { zodResolver } from '@hookform/resolvers/zod'
import { Plus, Trash2 } from 'lucide-react'
import { useFieldArray, useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Notice } from '@/components/ui/notice'
import { useEvents } from '@/features/events/api'
import { useCreateEvent } from '@/features/organizer/api'
import { ApiError } from '@/lib/api'
import { formatDateTime } from '@/lib/format'

/** "A-J" becomes A to J, "1-12" becomes 1 to 12, and "A, B, VIP" is taken as written. */
export function parseRows(input: string): string[] {
  const range = input.trim().match(/^([A-Za-z]|\d+)\s*-\s*([A-Za-z]|\d+)$/)
  if (range) {
    const [, from, to] = range
    if (/\d/.test(from) && /\d/.test(to)) {
      const start = Number(from)
      const end = Number(to)
      return start <= end ? Array.from({ length: end - start + 1 }, (_, i) => String(start + i)) : []
    }
    const start = from.toUpperCase().charCodeAt(0)
    const end = to.toUpperCase().charCodeAt(0)
    return start <= end ? Array.from({ length: end - start + 1 }, (_, i) => String.fromCharCode(start + i)) : []
  }
  return input.split(',').map((row) => row.trim()).filter(Boolean)
}

const sectionSchema = z.object({
  name: z.string().trim().min(1, 'Name the section.').max(40),
  rows: z.string().refine((value) => {
    const rows = parseRows(value)
    return rows.length > 0 && rows.length <= 50 && rows.every((row) => /^[A-Za-z0-9]{1,8}$/.test(row))
  }, 'Use a range like A-J or a list like A, B, C (up to 50 rows).'),
  seatsPerRow: z.coerce.number<string>().int().min(1, 'At least 1.').max(100, 'At most 100.'),
  price: z.coerce.number<string>().min(0, 'Use 0 for free seats.'),
})

const eventSchema = z
  .object({
    name: z.string().trim().min(1, 'Give the event a name.').max(120),
    venue: z.string().trim().min(1, 'Where is it?').max(120),
    startsAt: z.string().min(1, 'When does it start?'),
    salesOpenAt: z.string(),
    waitingRoom: z.boolean(),
    admitsPerMinute: z.coerce.number<string>().int().min(1).max(60_000),
    sections: z.array(sectionSchema).min(1),
  })
  .refine((event) => Date.parse(event.startsAt) > Date.now(), {
    path: ['startsAt'],
    message: 'The event must start in the future.',
  })
  .refine((event) => !event.salesOpenAt || Date.parse(event.salesOpenAt) < Date.parse(event.startsAt), {
    path: ['salesOpenAt'],
    message: 'Sales must open before the event starts.',
  })

type EventForm = z.input<typeof eventSchema>

function localInput(date: Date): string {
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

export function OrganizerPage() {
  return (
    <div className="grid grid-cols-1 gap-12 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <CreateEvent />
      <OrganizerEvents />
    </div>
  )
}

function CreateEvent() {
  const create = useCreateEvent()
  const navigate = useNavigate()
  const form = useForm<EventForm, unknown, z.output<typeof eventSchema>>({
    resolver: zodResolver(eventSchema),
    defaultValues: {
      name: '',
      venue: '',
      startsAt: localInput(new Date(Date.now() + 7 * 24 * 3600 * 1000)),
      salesOpenAt: localInput(new Date(Date.now() + 60 * 1000)),
      waitingRoom: true,
      admitsPerMinute: '120',
      sections: [{ name: 'Floor', rows: 'A-J', seatsPerRow: '20', price: '250' }],
    },
  })
  const sections = useFieldArray({ control: form.control, name: 'sections' })
  const errors = form.formState.errors
  const waitingRoom = form.watch('waitingRoom')

  const submit = form.handleSubmit((values) => {
    create.mutate(
      {
        name: values.name,
        venue: values.venue,
        startsAt: new Date(values.startsAt).toISOString(),
        salesOpenAt: values.salesOpenAt ? new Date(values.salesOpenAt).toISOString() : undefined,
        admitsPerMinute: values.waitingRoom ? values.admitsPerMinute : undefined,
        sections: values.sections.map((section) => ({
          name: section.name,
          rows: parseRows(section.rows),
          seatsPerRow: section.seatsPerRow,
          priceCents: Math.round(section.price * 100),
        })),
      },
      { onSuccess: (event) => navigate(`/organizer/events/${event.id}`) },
    )
  })

  return (
    <section aria-labelledby="create-title">
      <h1 id="create-title" className="text-6xl font-extrabold">New event</h1>
      <p className="mt-3 max-w-xl text-lg text-ink-soft">
        The seat map is generated from the sections. A waiting room spreads the opening rush over minutes.
      </p>
      <form onSubmit={submit} noValidate className="mt-8 flex flex-col gap-6">
        {create.isError && (
          <Notice tone="alert">
            {create.error.message}
            {create.error instanceof ApiError && create.error.fieldErrors.length > 0 && (
              <ul className="mt-1 list-disc pl-5 text-sm">
                {create.error.fieldErrors.map((error) => <li key={error.field}>{error.field}: {error.message}</li>)}
              </ul>
            )}
          </Notice>
        )}
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label="Name" placeholder="Rock in Rio" {...form.register('name')} error={errors.name?.message} />
          <Field label="Venue" placeholder="Parque Olímpico" {...form.register('venue')} error={errors.venue?.message} />
          <Field label="Starts at" type="datetime-local" {...form.register('startsAt')} error={errors.startsAt?.message} />
          <Field label="Sales open at" type="datetime-local" hint="Leave empty to sell right away."
            {...form.register('salesOpenAt')} error={errors.salesOpenAt?.message} />
        </div>

        <fieldset className="rounded-lg bg-paper-raised p-5 ring-1 ring-line">
          <legend className="sr-only">Waiting room</legend>
          <label className="flex items-start gap-3 text-base">
            <input type="checkbox" className="mt-1 size-5 accent-velvet" {...form.register('waitingRoom')} />
            <span>
              <span className="font-semibold">Open the sale behind a waiting room</span>
              <span className="block text-sm text-ink-soft">Buyers are let in at a steady pace, in the order they join.</span>
            </span>
          </label>
          {waitingRoom && (
            <Field className="mt-4 max-w-xs" label="Buyers let in per minute" type="number" min={1} max={60000}
              {...form.register('admitsPerMinute')} error={errors.admitsPerMinute?.message} />
          )}
        </fieldset>

        <div className="flex flex-col gap-4">
          <h2 className="text-3xl font-bold">Sections</h2>
          {sections.fields.map((field, index) => (
            <fieldset key={field.id} className="grid gap-4 rounded-lg bg-paper-raised p-5 ring-1 ring-line
              sm:grid-cols-[1fr_1fr_7rem_8rem_auto] sm:items-start">
              <legend className="sr-only">Section {index + 1}</legend>
              <Field label="Section" {...form.register(`sections.${index}.name`)}
                error={errors.sections?.[index]?.name?.message} />
              <Field label="Rows" placeholder="A-J" {...form.register(`sections.${index}.rows`)}
                error={errors.sections?.[index]?.rows?.message} />
              <Field label="Seats per row" type="number" min={1} max={100} {...form.register(`sections.${index}.seatsPerRow`)}
                error={errors.sections?.[index]?.seatsPerRow?.message} />
              <Field label="Price (R$)" type="number" min={0} step="0.01" {...form.register(`sections.${index}.price`)}
                error={errors.sections?.[index]?.price?.message} />
              <button type="button" onClick={() => sections.remove(index)} disabled={sections.fields.length === 1}
                aria-label={`Remove section ${index + 1}`}
                className="mt-7 inline-flex size-11 items-center justify-center rounded-md text-ink-soft
                  hover:bg-paper hover:text-alert disabled:invisible">
                <Trash2 aria-hidden className="size-5" />
              </button>
            </fieldset>
          ))}
          <Button type="button" variant="quiet" className="self-start"
            onClick={() => sections.append({ name: '', rows: 'A-E', seatsPerRow: '20', price: '150' })}>
            <Plus aria-hidden className="size-4" />
            Add a section
          </Button>
        </div>

        <Button type="submit" className="self-start" busy={create.isPending}>Create event</Button>
      </form>
    </section>
  )
}

function OrganizerEvents() {
  const events = useEvents()
  return (
    <section aria-labelledby="events-title" className="lg:pt-4">
      <h2 id="events-title" className="text-3xl font-bold">Live sales</h2>
      <p className="mt-2 text-base text-ink-soft">Open one to watch it and to send a simulated crowd.</p>
      {events.isError && <div className="mt-4"><Notice tone="alert">{events.error.message}</Notice></div>}
      <ul className="mt-5 flex flex-col divide-y divide-line">
        {events.data?.content.map((event) => (
          <li key={event.id}>
            <Link to={`/organizer/events/${event.id}`} className="block py-3 hover:text-velvet">
              <span className="block font-display text-xl font-bold">{event.name}</span>
              <span className="text-sm text-ink-soft">{formatDateTime(event.startsAt)}</span>
            </Link>
          </li>
        ))}
        {events.data?.content.length === 0 && <li className="py-3 text-ink-soft">Your events will show up here.</li>}
      </ul>
    </section>
  )
}
