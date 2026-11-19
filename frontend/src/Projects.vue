<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError } from './api'

type Status = 'ACTIVE' | 'ARCHIVED'
type Role = 'PROJECT_MANAGER' | 'CONTRACT_EDITOR' | 'PROJECT_VIEWER'

interface Project {
  id: string
  code: string
  name: string
  status: Status
  version: string
}

interface Detail extends Project {
  canEdit: boolean
  canManageMembers: boolean
}

interface Assignment {
  userId: string
  roles: Role[]
}

interface MemberOption {
  userId: string
  displayName: string
  email: string
}

interface Members {
  assignments: Assignment[]
  eligibleMembers: MemberOption[]
  version: string
}

const props = defineProps<{ administrator: boolean }>()
const emit = defineEmits<{ unauthorized: [] }>()
const roles: { value: Role; label: string }[] = [
  { value: 'PROJECT_MANAGER', label: 'Project manager' },
  { value: 'CONTRACT_EDITOR', label: 'Contract editor' },
  { value: 'PROJECT_VIEWER', label: 'Project viewer' },
]
const projects = ref<Project[]>([])
const selected = ref<Detail | null>(null)
const canCreate = ref(false)
const busy = ref(false)
const error = ref('')
const page = ref(1)
const total = ref(0)
const size = 25
const showDetails = ref(false)
const showMembers = ref(false)
const detailsMode = ref<'create' | 'edit'>('create')
const code = ref('')
const name = ref('')
const status = ref<Status>('ACTIVE')
const memberVersion = ref('')
const assignments = ref<Assignment[]>([])
const eligible = ref<MemberOption[]>([])
const newMember = ref('')
const eligibleById = computed(() => new Map(eligible.value.map(member => [member.userId, member])))
const addable = computed(() => eligible.value.filter(member =>
  !assignments.value.some(assignment => assignment.userId === member.userId)))
const path = '/projects'

async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The project request failed'
    if (cause instanceof ApiError && cause.status === 401) emit('unauthorized')
  } finally {
    busy.value = false
  }
}

async function loadPage(next = page.value) {
  const result = await api<{ items: Project[]; total: number; canCreate: boolean }>(
    path + '?page=' + (next - 1) + '&size=' + size)
  page.value = next
  projects.value = result.items
  total.value = result.total
  canCreate.value = result.canCreate
}

onMounted(() => run(() => loadPage()))

function setPage(next: number) {
  return run(() => loadPage(next))
}

function openProject(id: string) {
  return run(async () => {
    selected.value = await api<Detail>(path + '/' + id)
  })
}

function returnToList() {
  selected.value = null
  error.value = ''
}

function editDetails(mode: 'create' | 'edit') {
  detailsMode.value = mode
  const project = mode === 'edit' ? selected.value : null
  code.value = project?.code ?? ''
  name.value = project?.name ?? ''
  status.value = project?.status ?? 'ACTIVE'
  error.value = ''
  showDetails.value = true
}

function saveDetails() {
  return run(async () => {
    const project = detailsMode.value === 'edit' ? selected.value : null
    const fields = { code: code.value.trim(), name: name.value.trim() }
    selected.value = await api<Detail>(project ? path + '/' + project.id : path, {
      method: project ? 'PATCH' : 'POST',
      headers: project ? { 'If-Match': '"' + project.version + '"' } : undefined,
      body: JSON.stringify(project && props.administrator ? { ...fields, status: status.value } : fields),
    })
    showDetails.value = false
    await loadPage()
  })
}

function editMembers() {
  return run(async () => {
    if (!selected.value) return
    const members = await api<Members>(path + '/' + selected.value.id + '/members')
    assignments.value = members.assignments
    eligible.value = members.eligibleMembers
    memberVersion.value = members.version
    newMember.value = ''
    showMembers.value = true
  })
}

function addAssignment() {
  if (!newMember.value) return
  assignments.value.push({ userId: newMember.value, roles: ['PROJECT_VIEWER'] })
  newMember.value = ''
}

function removeAssignment(userId: string) {
  assignments.value = assignments.value.filter(item => item.userId !== userId)
}

function saveMembers() {
  return run(async () => {
    if (!selected.value) return
    const projectPath = path + '/' + selected.value.id
    const result = await api<Members>(projectPath + '/members', {
      method: 'PUT',
      headers: { 'If-Match': '"' + memberVersion.value + '"' },
      body: JSON.stringify({ assignments: assignments.value }),
    })
    selected.value = { ...selected.value, version: result.version }
    showMembers.value = false
    selected.value = await api<Detail>(projectPath)
  })
}
</script>

<template>
  <section class="panel projects" v-loading="busy">
    <el-alert v-if="error && !showDetails && !showMembers" :title="error" type="error" show-icon
      @close="error = ''" />
    <template v-if="selected">
      <el-button link type="primary" @click="returnToList">Back to projects</el-button>
      <div class="section-heading detail-heading">
        <div>
          <h2>{{ selected.name }}</h2>
          <p class="muted">{{ selected.code }} · {{ selected.status === 'ACTIVE' ? 'Active' : 'Archived' }}</p>
        </div>
        <div class="project-actions">
          <el-button v-if="selected.canManageMembers" @click="editMembers">Project members</el-button>
          <el-button v-if="selected.canEdit" type="primary" @click="editDetails('edit')">Edit project</el-button>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="section-heading">
        <h2>Projects</h2>
        <el-button v-if="canCreate" type="primary" @click="editDetails('create')">New project</el-button>
      </div>
      <el-table :data="projects" row-key="id" empty-text="No projects to display">
        <el-table-column label="Project" min-width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openProject(row.id)">{{ row.name }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="Code" min-width="100" />
        <el-table-column label="Status" min-width="100">
          <template #default="{ row }">{{ row.status === 'ACTIVE' ? 'Active' : 'Archived' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > size" layout="prev, pager, next" :current-page="page" :page-size="size"
        :total="total" :disabled="busy" @current-change="setPage" />
    </template>

    <el-dialog v-model="showDetails" :title="detailsMode === 'create' ? 'New project' : 'Edit project'"
      width="min(92vw, 460px)" :close-on-click-modal="!busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form label-position="top" @submit.prevent="saveDetails">
        <el-form-item label="Project name" for="project-name">
          <el-input id="project-name" v-model="name" maxlength="160" required />
        </el-form-item>
        <el-form-item label="Project code" for="project-code">
          <el-input id="project-code" v-model="code" maxlength="40" pattern="[A-Za-z0-9_\-]+" required />
        </el-form-item>
        <el-form-item v-if="detailsMode === 'edit' && administrator" label="Status">
          <el-select v-model="status" aria-label="Project status">
            <el-option label="Active" value="ACTIVE" />
            <el-option label="Archived" value="ARCHIVED" />
          </el-select>
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="!code.trim() || !name.trim()">
          {{ detailsMode === 'create' ? 'Create project' : 'Save changes' }}
        </el-button>
      </el-form>
    </el-dialog>

    <el-dialog v-model="showMembers" title="Project members" width="min(94vw, 720px)"
      :close-on-click-modal="!busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div class="assignment-picker">
        <el-select v-model="newMember" filterable aria-label="Organization member" placeholder="Select an active member">
          <el-option v-for="member in addable" :key="member.userId" :value="member.userId"
            :label="member.displayName + ' · ' + member.email" />
        </el-select>
        <el-button :disabled="!newMember || busy" @click="addAssignment">Add to project</el-button>
      </div>
      <ul v-if="assignments.length" class="assignments">
        <li v-for="assignment in assignments" :key="assignment.userId">
          <div class="section-heading">
            <strong>{{ eligibleById.get(assignment.userId)?.displayName ?? 'Inactive member #' + assignment.userId }}</strong>
            <el-button link type="danger" :disabled="busy" @click="removeAssignment(assignment.userId)">Remove</el-button>
          </div>
          <el-checkbox-group v-model="assignment.roles" :disabled="busy || !eligibleById.has(assignment.userId)"
            :aria-label="'Roles for member ' + assignment.userId">
            <el-checkbox v-for="role in roles" :key="role.value" :value="role.value">{{ role.label }}</el-checkbox>
          </el-checkbox-group>
        </li>
      </ul>
      <p v-else class="muted">No assigned project members.</p>
      <el-button type="primary" :loading="busy" :disabled="assignments.some(item => !item.roles.length)"
        @click="saveMembers">Save assignments</el-button>
    </el-dialog>
  </section>
</template>

<style scoped>
.detail-heading { flex-wrap: wrap; margin-top: 1rem; }
.detail-heading .muted { margin: .4rem 0 0; }
.project-actions, .assignment-picker { display: flex; flex-wrap: wrap; gap: .5rem; }
.project-actions .el-button + .el-button { margin-left: 0; }
.assignment-picker { margin-block: .75rem 1rem; }
.assignment-picker .el-select { flex: 1; min-width: min(100%, 15rem); }
.assignments { list-style: none; padding: 0; margin: 0 0 1rem; max-height: 45vh; overflow: auto; }
.assignments li { padding: .6rem 0; border-top: 1px solid #dbe3e7; }
.assignments .section-heading { margin-bottom: .25rem; }
</style>
