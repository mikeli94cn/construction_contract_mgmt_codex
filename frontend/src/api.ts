export type Role = 'ORG_ADMIN' | 'PARTNER_MANAGER' | 'MEMBER'

export const roles: { value: Role; label: string }[] = [
  { value: 'MEMBER', label: 'Member' },
  { value: 'PARTNER_MANAGER', label: 'Partner manager' },
  { value: 'ORG_ADMIN', label: 'Organization administrator' },
]

export interface Organization {
  id: string
  name: string
  roles: Role[]
}

export interface Profile {
  id: string
  displayName: string
  email: string
  activeOrganizationId: string | null
  organizations: Organization[]
}

export interface Member {
  userId: string
  email: string
  displayName: string
  accountStatus: 'ACTIVE' | 'PENDING' | 'DISABLED'
  enabled: boolean
  roles: Role[]
  version: string
}

export interface MemberPage {
  items: Member[]
  page: number
  size: number
  total: number
}

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function api<T>(path: string, request: RequestInit = {}): Promise<T> {
  const headers = new Headers(request.headers)
  if (request.body) headers.set('Content-Type', 'application/json')
  if (request.method && request.method !== 'GET') {
    const token = await api<{ headerName: string; token: string }>('/auth/csrf')
    headers.set(token.headerName, token.token)
  }
  const response = await fetch('/api/v1' + path, {
    ...request,
    headers,
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new ApiError(response.status, problem?.detail ?? 'The request could not be completed')
  }
  return response.status === 204 ? undefined as T : response.json()
}
