<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, roles } from './api'
import Projects from './Projects.vue'
import type { Member, MemberPage, Profile, Role } from './api'

const profile = ref<Profile | null>(null)
const section = ref<'projects' | 'members'>('projects')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const email = ref('')
const password = ref('')
const members = ref<Member[]>([])
const page = ref(1)
const size = 25
const total = ref(0)
const editor = ref(false)
const editing = ref<Member | null>(null)
const memberEmail = ref('')
const displayName = ref('')
const memberRoles = ref<Role[]>(['MEMBER'])
const enabled = ref(true)
const invite = ref('')
const activationToken = ref(new URLSearchParams(location.hash.slice(1)).get('activate') ?? '')
if (activationToken.value) history.replaceState(null, '', location.pathname + location.search)

const organization = computed(() =>
  profile.value?.organizations.find(item => item.id === profile.value?.activeOrganizationId))
const isAdmin = computed(() => organization.value?.roles.includes('ORG_ADMIN') ?? false)
const memberPath = computed(() => '/organizations/' + organization.value?.id + '/members')

function report(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : 'Something went wrong'
  if (cause instanceof ApiError && cause.status === 401) profile.value = null
}

async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (cause) {
    report(cause)
  } finally {
    busy.value = false
  }
}

async function loadMembers() {
  if (!isAdmin.value) return
  const result = await api<MemberPage>(memberPath.value + '?page=' + (page.value - 1) + '&size=' + size)
  members.value = result.items
  total.value = result.total
}

async function refresh() {
  section.value = 'projects'
  profile.value = await api<Profile>('/auth/me')
  page.value = 1
  members.value = []
  total.value = 0
  await loadMembers()
}

onMounted(async () => {
  try {
    if (!activationToken.value) await refresh()
  } catch (cause) {
    if (!(cause instanceof ApiError && cause.status === 401)) report(cause)
  } finally {
    loading.value = false
  }
})

function signIn() {
  return perform(async () => {
    await api('/auth/login', { method: 'POST', body: JSON.stringify({ email: email.value, password: password.value }) })
    password.value = ''
    await refresh()
  })
}

function activate() {
  return perform(async () => {
    await api('/auth/activation', { method: 'POST', body: JSON.stringify({
      token: activationToken.value, password: password.value,
    }) })
    activationToken.value = ''
    password.value = ''
  })
}

function signOut() {
  return perform(async () => {
    await api('/auth/logout', { method: 'POST' })
    profile.value = null
    members.value = []
    invite.value = ''
  })
}

function selectOrganization(id: string) {
  return perform(async () => {
    await api('/auth/organization', { method: 'PUT', body: JSON.stringify({ organizationId: id }) })
    invite.value = ''
    await refresh()
  })
}

function openEditor(member: Member | null) {
  editing.value = member
  memberEmail.value = member?.email ?? ''
  displayName.value = member?.displayName ?? ''
  memberRoles.value = member ? [...member.roles] : ['MEMBER']
  enabled.value = member?.enabled ?? true
  editor.value = true
}

function saveMember() {
  return perform(async () => {
    const member = editing.value
    if (member) {
      await api(memberPath.value + '/' + member.userId, {
        method: 'PATCH',
        headers: { 'If-Match': '"' + member.version + '"' },
        body: JSON.stringify({ roles: memberRoles.value, enabled: enabled.value }),
      })
    } else {
      const created = await api<{ activationToken: string | null }>(memberPath.value, {
        method: 'POST',
        body: JSON.stringify({ email: memberEmail.value, displayName: displayName.value, roles: memberRoles.value }),
      })
      invite.value = created.activationToken ? location.origin + '/#activate=' + created.activationToken : ''
    }
    editor.value = false
    await loadMembers()
  })
}

function setPage(next: number) {
  return perform(async () => {
    page.value = next
    await loadMembers()
  })
}
</script>

<template>
  <header class="top">
    <strong>Contract Management</strong>
    <div v-if="profile" class="user-controls">
      <el-select
        v-if="profile.organizations.length"
        aria-label="Active organization"
        placeholder="Select organization"
        :model-value="organization?.id"
        :disabled="busy"
        @change="selectOrganization"
      >
        <el-option v-for="org in profile.organizations" :key="org.id" :label="org.name" :value="org.id" />
      </el-select>
      <el-button :disabled="busy" @click="signOut">Sign out</el-button>
    </div>
  </header>
  <main v-loading="loading" :class="{ compact: !profile }">
    <el-alert v-if="error && !editor" :title="error" type="error" show-icon @close="error = ''" />
    <section v-if="!loading && !profile" class="panel auth-panel">
      <h1>{{ activationToken ? 'Activate your account' : 'Sign in' }}</h1>
      <el-form label-position="top" @submit.prevent="activationToken ? activate() : signIn()">
        <el-form-item v-if="!activationToken" label="Email address" for="email">
          <el-input id="email" v-model="email" type="email" autocomplete="username" required />
        </el-form-item>
        <el-form-item label="Password" for="password">
          <el-input id="password" v-model="password" type="password"
            :autocomplete="activationToken ? 'new-password' : 'current-password'" required />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy">
          {{ activationToken ? 'Activate account' : 'Sign in' }}
        </el-button>
      </el-form>
    </section>
    <template v-else-if="profile">
      <h1>{{ organization?.name ?? 'Your organizations' }}</h1>
      <p class="muted">Signed in as {{ profile.displayName }}</p>
      <el-alert v-if="!organization" type="info" :closable="false"
        :title="profile.organizations.length ? 'Select an organization to continue.' : 'No active organization membership.'" />
      <nav v-if="isAdmin" aria-label="Organization navigation" class="organization-nav">
        <el-button :type="section === 'projects' ? 'primary' : ''" @click="section = 'projects'">Projects</el-button>
        <el-button :type="section === 'members' ? 'primary' : ''" @click="section = 'members'">Organization members</el-button>
      </nav>
      <Projects v-if="organization && section === 'projects'" :key="organization.id" :administrator="isAdmin"
        @unauthorized="profile = null" />
      <section v-if="isAdmin && section === 'members'" class="panel">
        <div class="section-heading">
          <h2>Members</h2>
          <el-button type="primary" :disabled="busy" @click="openEditor(null)">Add member</el-button>
        </div>
        <div v-if="invite" class="invite">
          <label for="invite">One-time activation link</label>
          <el-input id="invite" :model-value="invite" readonly />
          <el-button @click="invite = ''">Dismiss</el-button>
        </div>
        <el-table :data="members" v-loading="busy" row-key="userId">
          <el-table-column label="Member" min-width="210">
            <template #default="{ row }"><strong>{{ row.displayName }}</strong><br />{{ row.email }}</template>
          </el-table-column>
          <el-table-column label="Roles" min-width="200">
            <template #default="{ row }">{{ roles.filter(role => row.roles.includes(role.value)).map(role => role.label).join(', ') }}</template>
          </el-table-column>
          <el-table-column label="Access" min-width="120">
            <template #default="{ row }">{{ row.enabled ? (row.accountStatus === 'PENDING' ? 'Pending activation' : row.accountStatus === 'ACTIVE' ? 'Active' : 'Account disabled') : 'Disabled' }}</template>
          </el-table-column>
          <el-table-column label="Actions" width="100">
            <template #default="{ row }"><el-button link type="primary" :aria-label="'Edit ' + row.displayName" @click="openEditor(row)">Edit</el-button></template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="total > size" layout="prev, pager, next" :current-page="page" :page-size="size"
          :total="total" :disabled="busy" @current-change="setPage" />
      </section>
    </template>
    <el-dialog v-model="editor" :title="editing ? 'Edit member' : 'Add member'" width="min(92vw, 460px)"
      @closed="error = ''">
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <el-form label-position="top" @submit.prevent="saveMember">
        <el-form-item v-if="!editing" label="Name" for="member-name">
          <el-input id="member-name" v-model="displayName" required maxlength="160" />
        </el-form-item>
        <el-form-item v-if="!editing" label="Email address" for="member-email">
          <el-input id="member-email" v-model="memberEmail" type="email" required />
        </el-form-item>
        <el-form-item label="Roles">
          <el-checkbox-group v-model="memberRoles">
            <el-checkbox v-for="role in roles" :key="role.value" :value="role.value">{{ role.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="editing" label="Membership access">
          <el-switch v-model="enabled" aria-label="Membership access" />
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!memberRoles.length">
          {{ editing ? 'Save changes' : 'Add member' }}
        </el-button>
      </el-form>
    </el-dialog>
  </main>
</template>

<style>
:root {
  font-family: system-ui, sans-serif;
  color: #202b36;
  background: #f1f4f6;
  --el-color-primary: #17685a;
}
body { margin: 0; }
.top { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem clamp(1rem, 5vw, 4rem); background: #142c2a; color: white; }
.user-controls { display: flex; gap: .5rem; align-items: center; }
.user-controls .el-select { width: clamp(125px, 24vw, 210px); }
main { margin: 3rem auto; width: min(calc(100% - 2rem), 1080px); min-height: 12rem; }
main.compact { max-width: 420px; }
.panel { padding: clamp(1rem, 4vw, 2rem); background: white; border: 1px solid #dbe3e7; border-radius: 12px; }
h1 { margin: 0 0 .5rem; font-size: clamp(1.75rem, 4vw, 2.3rem); }
h2 { margin: 0; }
.auth-panel h1 { margin-bottom: 1.5rem; }
.muted { color: #52616d; margin: 0 0 1.5rem; }
.section-heading, .invite { display: flex; gap: .75rem; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
.organization-nav { margin-bottom: 1rem; }
.invite { flex-wrap: wrap; background: #eef7f4; padding: .9rem; border-radius: 6px; }
.el-alert, .el-pagination { margin-block: .75rem; }
@media (max-width: 520px) {
  .top { flex-wrap: wrap; }
  main { margin-top: 1.5rem; }
}
</style>
