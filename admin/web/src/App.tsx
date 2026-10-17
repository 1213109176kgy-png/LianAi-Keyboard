import { Navigate, Route, Routes } from 'react-router-dom'
import AdminLayout from './components/AdminLayout'
import Login from './pages/Login'
import Versions from './pages/Versions'
import OssConfig from './pages/OssConfig'
import PaymentConfig from './pages/PaymentConfig'
import Plans from './pages/Plans'
import Models from './pages/Models'
import Costs from './pages/Costs'
import Users from './pages/Users'
import Orders from './pages/Orders'
import Dau from './pages/Dau'
import Business from './pages/Business'
import ChangePassword from './pages/ChangePassword'

function RequireAuth({ children }: { children: React.ReactNode }) {
  if (!localStorage.getItem('lianai_admin_token')) {
    return <Navigate to="/login" replace />
  }
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <AdminLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/versions" replace />} />
        <Route path="versions" element={<Versions />} />
        <Route path="oss" element={<OssConfig />} />
        <Route path="payment" element={<PaymentConfig />} />
        <Route path="plans" element={<Plans />} />
        <Route path="models" element={<Models />} />
        <Route path="business" element={<Business />} />
        <Route path="costs" element={<Costs />} />
        <Route path="users" element={<Users />} />
        <Route path="orders" element={<Orders />} />
        <Route path="dau" element={<Dau />} />
        <Route path="password" element={<ChangePassword />} />
      </Route>
      <Route path="*" element={<Navigate to="/versions" replace />} />
    </Routes>
  )
}
