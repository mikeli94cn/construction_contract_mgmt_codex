import { createApp } from 'vue'
import {
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElForm, ElFormItem,
  ElInput, ElLoading, ElOption, ElPagination, ElSelect, ElSwitch, ElTable, ElTableColumn,
} from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'

const app = createApp(App)
const components = {
  ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElDialog, ElForm, ElFormItem,
  ElInput, ElOption, ElPagination, ElSelect, ElSwitch, ElTable, ElTableColumn,
}
for (const [name, component] of Object.entries(components)) {
  app.component(name, component)
}
app.use(ElLoading)
app.mount('#app')
