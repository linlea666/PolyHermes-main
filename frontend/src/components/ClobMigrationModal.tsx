import { Modal, Button, Space } from 'antd'
import { SwapOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'

const STORAGE_KEY = 'clob_v2_migration_dismissed'

interface ClobMigrationModalProps {
  open: boolean
  onClose: () => void
}

const ClobMigrationModal: React.FC<ClobMigrationModalProps> = ({ open, onClose }) => {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const handleClose = (dontRemind = false) => {
    if (dontRemind) {
      localStorage.setItem(STORAGE_KEY, 'true')
    }
    onClose()
  }

  const handleGoToAccounts = () => {
    localStorage.setItem(STORAGE_KEY, 'true')
    onClose()
    navigate('/accounts')
  }

  return (
    <Modal
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <SwapOutlined style={{ color: '#fa8c16' }} />
          <span>{t('clobMigration.title')}</span>
        </div>
      }
      open={open}
      onCancel={() => handleClose()}
      footer={
        <Space>
          <Button onClick={() => handleClose(true)}>
            {t('clobMigration.dontRemind')}
          </Button>
          <Button onClick={() => handleClose()}>
            {t('common.later')}
          </Button>
          <Button type="primary" onClick={handleGoToAccounts}>
            {t('clobMigration.goToAccounts')}
          </Button>
        </Space>
      }
      closable
      maskClosable={false}
    >
      <p style={{ fontSize: 14, lineHeight: 1.8, color: 'rgba(0, 0, 0, 0.65)' }}>
        {t('clobMigration.description')}
      </p>
    </Modal>
  )
}

export default ClobMigrationModal
export { STORAGE_KEY }
