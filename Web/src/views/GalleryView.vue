<template>
  <div class="gallery-page">
    <!-- Top Bar -->
    <header class="top-bar">
      <template v-if="isSelecting">
        <button class="btn-icon" @click="clearSelection">
          <span>✕</span>
        </button>
        <span class="selected-count">{{ selectedIds.size }} 已选中</span>
        <div class="top-actions">
          <button v-if="selectedIds.size === 1" class="btn-text" @click="previewSelected">预览</button>
          <button class="btn-text" @click="selectAll">{{ selectedIds.size === allCurrentIds.length ? '取消全选' : '全选' }}</button>
          <button class="btn-text" @click="handleDelete">删除</button>
        </div>
      </template>
      <template v-else>
        <h2>云相册</h2>
        <div class="header-actions">
          <button class="btn-icon upload-btn" @click="triggerUpload" title="上传照片">+</button>
          <button class="btn-icon" @click="handleRefresh" title="刷新">↻</button>
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
    </div>

    <!-- Gallery Groups -->
    <div v-else class="groups">
      <div v-for="group in groups" :key="group.date" class="group">
        <div class="group-header">{{ group.date }}</div>
        <div class="photos">
          <div
            v-for="photo in group.photos"
            :key="photo.id"
            class="photo-item"
            :class="{ selected: selectedIds.has(photo.id), selecting: isSelecting && !selectedIds.has(photo.id) }"
            @click="onPhotoClick(photo)"
          >
            <img
              :src="getThumbnailUrl(photo)"
              :alt="photo.name"
              loading="lazy"
            />
            <div v-show="isSelecting && selectedIds.has(photo.id)" class="photo-check">✓</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Preview Modal -->
    <div v-if="previewPhoto" class="preview-modal" @click.self="closePreview">
      <button class="preview-close" @click="closePreview">✕</button>
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
        <p v-if="localCount > 0">
          确定删除这 {{ deleteCount }} 张照片？<br/>
          其中 {{ localCount }} 张本地也有副本，是否一并删除？
        </p>
        <p v-else>确定删除这 {{ deleteCount }} 张照片？</p>
        <div class="dialog-actions">
          <button v-if="localCount > 0" class="btn-danger" @click="confirmDelete(true)">
            删除云端和本地
          </button>
          <button v-if="localCount > 0" @click="confirmDelete(false)">只删云端</button>
          <button v-else class="btn-danger" @click="confirmDelete(false)">删除</button>
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
const groups = ref<GalleryGroup[]>([])
const loading = ref(true)
const error = ref('')
const isSelecting = ref(false)
const selectedIds = ref(new Set<string>())
const previewPhoto = ref<GalleryPhoto & { url: string } | null>(null)
const showDeleteDialog = ref(false)
const toast = ref('')
const thumbnailUrls = ref(new Map<string, string>())
const deleteCount = ref(0)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadTotal = ref(0)
const uploadCurrent = ref(0)
const uploadProgress = ref(0)
const uploadFileName = ref('')

const allCurrentIds = computed(() => groups.value.flatMap(g => g.photos).map(p => p.id))

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

function onPhotoClick(photo: GalleryPhoto) {
  if (isSelecting.value) {
    toggleSelect(photo.id)
  } else {
    // 进入选择模式
    isSelecting.value = true
    // 延迟一下再选中，避免状态切换时重渲染冲突
    setTimeout(() => toggleSelect(photo.id), 10)
  }
}

function toggleSelect(id: string) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selectedIds.value = s
  if (s.size === 0) isSelecting.value = false
}

function selectAll() {
  if (selectedIds.value.size === allCurrentIds.value.length) {
    clearSelection()
  } else {
    selectedIds.value = new Set(allCurrentIds.value)
  }
}

async function previewSelected() {
  if (selectedIds.value.size !== 1) return
  const id = Array.from(selectedIds.value)[0]
  const photo = groups.value.flatMap(g => g.photos).find(p => p.id === id)
  if (photo) {
    const url = await api.getPhotoUrl(photo.path)
    previewPhoto.value = { ...photo, url }
  }
}

function closePreview() {
  previewPhoto.value = null
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

function handleDelete() {
  if (selectedIds.value.size === 0) return
  const photos = groups.value.flatMap(g => g.photos).filter(p => selectedIds.value.has(p.id))
  const localCount = photos.filter(p => p.hasLocal).length
  deleteCount.value = selectedIds.value.size
  if (localCount > 0) {
    showDeleteDialog.value = true
  } else {
    confirmDelete(false)
  }
}

const localCount = computed(() => {
  const photos = groups.value.flatMap(g => g.photos).filter(p => selectedIds.value.has(p.id))
  return photos.filter(p => p.hasLocal).length
})

async function confirmDelete(_alsoDeleteLocal: boolean) {
  showDeleteDialog.value = false
  const paths = groups.value
    .flatMap(g => g.photos)
    .filter(p => selectedIds.value.has(p.id))
    .map(p => p.path)
  try {
    const resp = await api.deletePhotos(paths)
    const msg = resp.failed.length > 0
      ? `${resp.deleted.length} 张成功，${resp.failed.length} 张失败`
      : `已删除 ${resp.deleted.length} 张照片`
    showToast(msg)
    selectedIds.value = new Set()
    isSelecting.value = false
    await loadGallery()
  } catch (e: any) {
    showToast('删除失败: ' + (e?.message || ''))
  }
}

function clearSelection() {
  selectedIds.value = new Set()
  isSelecting.value = false
}

async function handleRefresh() {
  await loadGallery()
}

function handleLogout() {
  api.clearAuth()
  router.push('/login')
}

function showToast(msg: string) {
  toast.value = msg
  setTimeout(() => { toast.value = '' }, 3000)
}

function triggerUpload() {
  fileInputRef.value?.click()
}

async function onFilesSelected(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (files.length === 0) return

  uploading.value = true
  uploadTotal.value = files.length
  uploadCurrent.value = 0
  uploadProgress.value = 0

  let success = 0
  let failed = 0

  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    uploadCurrent.value = i + 1
    uploadFileName.value = file.name
    uploadProgress.value = 0

    try {
      await api.uploadPhoto(file, (pct) => {
        uploadProgress.value = pct
      })
      success++
    } catch {
      failed++
    }
  }

  uploading.value = false
  uploadFileName.value = ''

  const msg = failed === 0
    ? `上传成功 ${success} 张`
    : `${success} 张成功，${failed} 张失败`
  showToast(msg)

  input.value = '' // reset input
  if (success > 0) {
    await loadGallery()
  }
}

onMounted(() => {
  loadGallery()
})
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

.top-bar h2 {
  margin: 0;
  font-size: 1.1rem;
}

.top-bar .selected-count {
  flex: 1;
  font-weight: 500;
}

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

.loading, .error-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 1rem;
  gap: 1rem;
  color: #666;
}

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

.photo-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.photo-item.selected {
  outline: 3px solid #4f8aff;
  outline-offset: -2px;
}

.photo-item.selecting {
  outline: 1px dashed #4f8aff;
  outline-offset: -1px;
}

.photo-check {
  position: absolute;
  top: 4px;
  right: 4px;
  background: #4f8aff;
  color: white;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.checkbox {
  position: absolute;
  top: 6px;
  left: 6px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid white;
  background: rgba(0,0,0,0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: white;
}

.checkbox.on {
  background: #4f8aff;
  border-color: #4f8aff;
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

.preview-img {
  max-width: 90vw;
  max-height: 75vh;
  object-fit: contain;
}

.preview-info {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: linear-gradient(transparent, rgba(0,0,0,0.8));
  padding: 2rem 1rem 1rem;
  color: white;
}

.preview-name {
  margin: 0 0 0.25rem;
  font-size: 1rem;
  word-break: break-all;
}

.preview-size {
  margin: 0;
  font-size: 0.85rem;
  color: #ccc;
}

.preview-actions {
  margin-top: 0.75rem;
  display: flex;
  gap: 0.75rem;
}

.preview-actions button {
  padding: 0.4rem 1rem;
  border-radius: 6px;
  border: none;
  background: rgba(255,255,255,0.2);
  color: white;
  cursor: pointer;
}

.preview-actions .btn-danger {
  background: #e53e3e;
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

.upload-name {
  font-size: 0.8rem;
  color: #888;
  word-break: break-all;
  margin: 0 !important;
}

.upload-dialog p {
  margin: 0 0 0.25rem;
  color: #555;
}

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

.dialog h3 {
  margin: 0 0 0.75rem;
}

.dialog p {
  margin: 0 0 1.25rem;
  color: #555;
  font-size: 0.95rem;
  line-height: 1.5;
}

.dialog-actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.dialog-actions button {
  width: 100%;
  padding: 0.6rem;
  border-radius: 8px;
  border: 1px solid #ddd;
  background: white;
  cursor: pointer;
  font-size: 0.95rem;
}

.dialog-actions .btn-danger {
  background: #e53e3e;
  color: white;
  border: none;
}

.toast {
  position: fixed;
  bottom: 2rem;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(0,0,0,0.8);
  color: white;
  padding: 0.6rem 1.2rem;
  border-radius: 8px;
  font-size: 0.9rem;
  z-index: 300;
}
</style>
