import { createRouter, createWebHistory } from 'vue-router'
import { api } from '../api/client'

const routes = [
  {
    path: '/',
    redirect: '/gallery',
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
  },
  {
    path: '/gallery',
    name: 'Gallery',
    component: () => import('../views/GalleryView.vue'),
    meta: { requiresAuth: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !api.isLoggedIn()) {
    return '/login'
  }
  if (to.path === '/login' && api.isLoggedIn()) {
    return '/gallery'
  }
})

export default router
