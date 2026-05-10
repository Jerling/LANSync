<template>
  <div class="gallery-page">
    <!-- Top Bar — only this part uses Vue reactivity during selection -->
    <header class="top-bar">
      <template v-if="selecting">
        <button class="btn-icon" @click="exitSelection">
          <span>✕</span>
        </button>
        <span class="selected-count">{{ selectedCount }} 已选中</span>
        <div class="top-actions">
          <button v-if="selectedCount === 1" class="btn-text" @click="previewSelectedIds">预览</button>
          <button class="btn-text" @click="toggleSelectAll">{{ selectedCount === totalCount && totalCount > 0 ? '取消全选' : '全选' }}</button>
          <button class="btn-text btn-danger-text" @click="showDeleteDialog = true">删除</button>
        </div>
      </template>
      <template v-else>
        <h2>云相册</h2>
        <div class="header-actions">
          <button class="btn-icon upload-btn" @click="triggerUpload" :title="pendingFiles.length > 0 ? `上传 ${pendingFiles.length} 个文件` : '上传照片'">
            {{ pendingFiles.length > 0 ? pendingFiles.length : '+' }}
          </button>
          <button class="btn-icon" @click="loadGallery" title="刷新">↻</button>
          <button class="btn-icon" @click="handleLogout" title="退出">⎋</button>
        </div>
      </template>
    </header>

    <!-- Loading -->
    <div v-if="loading" class="loading">
      <div class="spinner"></div>
      <p>加载中...</p>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="error-state">
      <p>{{ error }}</p>
      <button @click="loadGallery">重试</button>
    </div>

    <!-- Empty -->
    <div v-else-if="groups.length === 0" class="empty-state">
      <p>暂无照片</p>
      <p class="empty-hint">点击右上角 + 上传照片</p>
    </div>

    <!--
      Gallery grid: selecting state is CSS-driven via container class.
      No Vue reactivity on individual photo items — all selection is pure JS + CSS.
    -->
    <div
      v-else
      class="groups"
      :class="{ 'is-selecting': selecting }"
      @click="onGalleryClick"
    >
      <div v-for="group in groups" :key="group.date" class="group">
        <div class="group-header">{{ group.date }}</div>
        <div class="photos">
          <div
            v-for="photo in group.photos"
            :key="photo.id"
            class="photo-item"
            :data-id="photo.id"
            :data-selected="isSelected(photo.id)"
          >
            <img
              :src="getThumbnailUrl(photo)"
              :alt="photo.name"
              loading="lazy"
            />
            <div class="photo-check">✓</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Preview Modal -->
    <div v-if="previewPhoto" class="preview-modal" @click.self="previewPhoto = null">
      <button class="preview-close" @click="previewPhoto = null">✕</button>
      <img :src="previewPhoto.url" :alt="previewPhoto.name" class="preview-img" />
      <div class="preview-info">
        <p class="preview-name">{{ previewPhoto.name }}</p>
        <p class="preview-size">{{ formatSize(previewPhoto.size) }}</p>
        <div class="preview-actions">
          <button @click="downloadPhoto(previewPhoto)">下载</button>
        </div>
      </div>
    </div>

    <!-- Delete Confirm Dialog -->
    <div v-if="showDeleteDialog" class="dialog-overlay">
      <div class="dialog">
        <h3>删除照片</h3>
        <p>确定删除这 {{ selectedCount }} 张照片？</p>
        <div class="dialog-actions">
          <button class="btn-danger" @click="confirmDelete">删除</button>
          <button @click="showDeleteDialog = false">取消</button>
        </div>
      </div>
    </div>

    <!-- Upload Progress Dialog -->
    <div v-if="uploading" class="dialog-overlay">
      <div class="dialog upload-dialog">
        <h3>上传中...</h3>
        <p>{{ uploadCurrent }} / {{ uploadTotal }}</p>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: uploadProgress + '%' }"></div>
        </div>
        <p class="upload-name" v-if="uploadFileName">{{ uploadFileName }}</p>
      </div>
    </div>

    <!-- Toast -->
    <div v-if="toast" class="toast">{{ toast }}</div>

    <!-- Hidden file input -->
    <input
      ref="fileInputRef"
      type="file"
      accept="image/*,video/*"
      multiple
      style="display:none"
      @change="onFilesSelected"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, type GalleryGroup, type GalleryPhoto } from '../api/client'

const router = useRouter()

// --- Gallery data (normal Vue reactivity) ---
const groups = ref<GalleryGroup[]>([])
const loading = ref(true)
const error = ref('')
const thumbnailUrls = ref(new Map<string, string>())
const toast = ref('')

// --- Upload state ---
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadTotal = ref(0)
const uploadCurrent = ref(0)
const uploadProgress = ref(0)
const uploadFileName = ref('')

// --- Delete dialog ---
const showDeleteDialog = ref(false)

// --- Preview ---
const previewPhoto = ref<(GalleryPhoto & { url: string }) | null>(null)

// --- Selection: ONLY these are reactive ---
const selecting = ref(false)
const selectedIds = ref(new Set<string>())

const selectedCount = computed(() => selectedIds.value.size)
const totalCount = computed(() => groups.value.reduce((sum, g) => sum + g.photos.length, 0))

function isSelected(id: string): string {
  return selectedIds.value.has(id) ? 'true' : 'false'
}

// --- DOM event delegation (single handler on container) ---
function onGalleryClick(e: MouseEvent) {
  const target = (e.target as HTMLElement).closest('.photo-item') as HTMLElement | null
  if (!target) return
  const id = target.dataset['id']
  if (!id) return

  if (!selecting.value) {
    selecting.value = true
    selectedIds.value = new Set([id])
  } else {
    const s = new Set(selectedIds.value)
    if (s.has(id)) {
      s.delete(id)
      if (s.size === 0) {
        selecting.value = false
        selectedIds.value = new Set()
        return
      }
    } else {
      s.add(id)
    }
    selectedIds.value = s
  }
}

function exitSelection() {
  selecting.value = false
  selectedIds.value = new Set()
}

function toggleSelectAll() {
  if (selectedCount.value === totalCount.value && totalCount.value > 0) {
    exitSelection()
  } else {
    const allIds = new Set(groups.value.flatMap(g => g.photos.map(p => p.id)))
    selectedIds.value = allIds
    selecting.value = true
  }
}

async function previewSelectedIds() {
  if (selectedIds.value.size !== 1) return
  const id = Array.from(selectedIds.value)[0]
  const photo = groups.value.flatMap(g => g.photos).find(p => p.id === id)
  if (!photo) return
  const url = await api.getPhotoUrl(photo.path)
  previewPhoto.value = { ...photo, url }
}

async function confirmDelete() {
  showDeleteDialog.value = false
  const paths = groups.value
    .flatMap(g => g.photos)
    .filter(p => selectedIds.value.has(p.id))
    .map(p => p.path)

  let deleted = 0, failed = 0
  for (const path of paths) {
    try {
      await api.deletePhotos([path])
      deleted++
    } catch {
      failed++
    }
  }

  showToast(failed === 0 ? `已删除 ${deleted} 张` : `${deleted} 张成功，${failed} 张失败`)
  exitSelection()
  await loadGallery()
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function getThumbnailUrl(photo: GalleryPhoto): string {
  return thumbnailUrls.value.get(photo.id) || ''
}

async function loadGallery() {
  loading.value = true
  error.value = ''
  thumbnailUrls.value = new Map()
  try {
    groups.value = await api.getGallery()
    for (const group of groups.value) {
      for (const photo of group.photos) {
        api.getPhotoUrl(photo.path).then(url => {
          thumbnailUrls.value.set(photo.id, url)
        })
      }
    }
  } catch (e: any) {
    error.value = '加载失败: ' + (e?.message || '未知错误')
  } finally {
    loading.value = false
  }
}

async function downloadPhoto(photo: GalleryPhoto) {
  try {
    const blob = await api.downloadPhoto(photo.path)
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = photo.name
    a.click()
    URL.revokeObjectURL(a.href)
    showToast('下载成功')
  } catch {
    showToast('下载失败')
  }
}

function handleLogout() {
  api.clearAuth()
  router.push('/login')
}

function showToast(msg: string) {
  toast.value = msg
  setTimeout(() => { toast.value = '' }, 3000)
}

// Store only file references (name + size) immediately after selection
// Actual file reading happens during upload, so UI never blocks
const pendingFiles = ref<{ name: string; file: File }[]>([])

function onFilesSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const fileList = input.files
  if (!fileList || fileList.length === 0) return

  // Store only metadata — no file content read yet
  pendingFiles.value = Array.from(fileList).map(f => ({ name: f.name, file: f }))
  showToast(`已选择 ${pendingFiles.value.length} 个文件，点击上传`)
  input.value = '' // reset so same file can be re-selected
}

async function uploadPendingFiles() {
  const files = pendingFiles.value
  if (files.length === 0) return

  uploading.value = true
  uploadTotal.value = files.length
  uploadCurrent.value = 0
  uploadProgress.value = 0

  let success = 0, failed = 0
  for (let i = 0; i < files.length; i++) {
    const { name, file } = files[i]
    uploadCurrent.value = i + 1
    uploadFileName.value = name
    uploadProgress.value = 0
    try {
      await api.uploadPhoto(file, (pct) => { uploadProgress.value = pct })
      success++
    } catch {
      failed++
    }
  }

  uploading.value = false
  uploadFileName.value = ''
  pendingFiles.value = []
  showToast(failed === 0 ? `上传成功 ${success} 张` : `${success} 张成功，${failed} 张失败`)
  if (success > 0) await loadGallery()
}

// Override triggerUpload to trigger upload if pending, else open file picker
function triggerUpload() {
  if (pendingFiles.value.length > 0) {
    uploadPendingFiles()
  } else {
    fileInputRef.value?.click()
  }
}

onMounted(() => { loadGallery() })
</script>

<style scoped>
.gallery-page {
  min-height: 100vh;
  background: #f5f5f5;
}

.top-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  background: white;
  padding: 0.75rem 1rem;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  box-shadow: 0 1px 4px rgba(0,0,0,0.08);
}

.top-bar h2 { margin: 0; font-size: 1.1rem; }
.top-bar .selected-count { flex: 1; font-weight: 500; }

.header-actions, .top-actions {
  display: flex;
  gap: 0.25rem;
  margin-left: auto;
}

.btn-icon {
  width: 36px;
  height: 36px;
  border: none;
  background: #f0f0f0;
  border-radius: 8px;
  cursor: pointer;
  font-size: 1.1rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-text {
  padding: 0.35rem 0.75rem;
  border: none;
  background: #4f8aff;
  color: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.9rem;
}

.btn-danger-text {
  background: #e53e3e;
}

.loading, .error-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 1rem;
  gap: 1rem;
  color: #666;
}

.empty-hint { font-size: 0.85rem; color: #999; }

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e0e0e0;
  border-top-color: #4f8aff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.groups { padding: 0.75rem; }
.group { margin-bottom: 1.5rem; }

.group-header {
  font-size: 0.85rem;
  font-weight: 600;
  color: #666;
  margin-bottom: 0.5rem;
  padding: 0 0.25rem;
}

.photos {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 3px;
}

.photo-item {
  position: relative;
  aspect-ratio: 1;
  overflow: hidden;
  cursor: pointer;
  background: #eee;
  border-radius: 2px;
}

/* Selection is CSS-driven — data-selected attribute is toggled by Vue, classes are static */
.photo-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.photo-check {
  display: none;
  position: absolute;
  top: 4px;
  right: 4px;
  background: #4f8aff;
  color: white;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

/* Show check only when in selection mode AND item is selected */
.is-selecting .photo-item[data-selected="true"] .photo-check {
  display: flex;
}

.is-selecting .photo-item[data-selected="true"] {
  outline: 3px solid #4f8aff;
  outline-offset: -2px;
}

.is-selecting .photo-item {
  outline: 1px dashed #ccc;
  outline-offset: -1px;
}

.preview-modal {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0,0,0,0.92);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.preview-close {
  position: absolute;
  top: 1rem;
  right: 1rem;
  background: rgba(255,255,255,0.15);
  border: none;
  color: white;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  font-size: 1.2rem;
  cursor: pointer;
}

.preview-img { max-width: 90vw; max-height: 75vh; object-fit: contain; }

.preview-info {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: linear-gradient(transparent, rgba(0,0,0,0.8));
  padding: 2rem 1rem 1rem;
  color: white;
}

.preview-name { margin: 0 0 0.25rem; font-size: 1rem; word-break: break-all; }
.preview-size { margin: 0; font-size: 0.85rem; color: #ccc; }
.preview-actions { margin-top: 0.75rem; display: flex; gap: 0.75rem; }
.preview-actions button {
  padding: 0.4rem 1rem;
  border-radius: 6px;
  border: none;
  background: rgba(255,255,255,0.2);
  color: white;
  cursor: pointer;
}

.upload-btn {
  background: #4f8aff !important;
  color: white !important;
  font-weight: bold;
  font-size: 1.3rem !important;
}

.progress-bar {
  height: 8px;
  background: #e0e0e0;
  border-radius: 4px;
  overflow: hidden;
  margin: 0.75rem 0 0.5rem;
}

.progress-fill {
  height: 100%;
  background: #4f8aff;
  border-radius: 4px;
  transition: width 0.2s;
}

.upload-name { font-size: 0.8rem; color: #888; word-break: break-all; margin: 0 !important; }
.upload-dialog p { margin: 0 0 0.25rem; color: #555; }

.dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}

.dialog {
  background: white;
  border-radius: 12px;
  padding: 1.5rem;
  width: 100%;
  max-width: 320px;
}

.dialog h3 { margin: 0 0 0.75rem; }
.dialog p { margin: 0 0 1.25rem; color: #555; }

.dialog-actions { display: flex; gap: 0.75rem; flex-wrap: wrap; }
.dialog-actions button {
  flex: 1;
  padding: 0.6rem;
  border-radius: 8px;
  border: none;
  cursor: pointer;
  font-size: 1rem;
  background: #f0f0f0;
}
.dialog-actions .btn-danger { background: #e53e3e; color: white; }

.toast {
  position: fixed;
  bottom: 2rem;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0,0,0,0.75);
  color: white;
  padding: 0.6rem 1.2rem;
  border-radius: 20px;
  font-size: 0.9rem;
  z-index: 300;
  white-space: nowrap;
}
</style>
