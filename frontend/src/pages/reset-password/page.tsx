import { useState } from 'react';
import { Link } from 'react-router-dom';
import ResetPasswordForm from './components/ResetPasswordForm';
import VerifyTempPasswordForm from './components/VerifyTempPasswordForm';
import ChangePasswordForm from './components/ChangePasswordForm';
import logoIcon from '@/assets/Logo.svg';

export default function ResetPasswordPage() {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [email, setEmail] = useState('');

  return (
    <div className="relative flex min-h-screen w-full items-center justify-center bg-background">
      {/* Background Gradient Effects */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        {/* Green Gradient */}
        <div
          className="absolute left-[-466px] top-[425px] size-[1348px] rounded-[1000px] opacity-[0.24] blur-[50px]"
          style={{
            background:
              'radial-gradient(circle, rgba(71,145,105,1) 0%, rgba(53,109,79,1) 25%, rgba(36,73,53,1) 50%, rgba(27,55,40,1) 62.5%, rgba(18,37,28,1) 75%, rgba(10,19,15,1) 87.5%, rgba(5,10,8,1) 93.75%, rgba(1,1,2,1) 100%)',
          }}
        />
        {/* Orange Gradient */}
        <div
          className="absolute right-[-16px] top-[-327px] size-[1054px] rounded-[1000px] opacity-[0.18] blur-[50px]"
          style={{
            background:
              'radial-gradient(circle, rgba(225,76,0,1) 0%, rgba(169,57,1,1) 25%, rgba(113,38,1,1) 50%, rgba(85,29,1,1) 62.5%, rgba(57,20,2,1) 75%, rgba(29,10,2,1) 87.5%, rgba(15,6,2,1) 93.75%, rgba(1,1,2,1) 100%)',
          }}
        />
        {/* Blue Gradient */}
        <div
          className="absolute left-[-277px] top-[-594px] h-[1111px] w-[1457px] rounded-[1000px] opacity-[0.2] blur-[50px]"
          style={{
            background:
              'radial-gradient(ellipse, rgba(86,150,184,1) 0%, rgba(64,113,138,1) 25%, rgba(43,76,93,1) 50%, rgba(33,57,70,1) 62.5%, rgba(22,38,47,1) 75%, rgba(12,20,25,1) 87.5%, rgba(6,10,13,1) 93.75%, rgba(1,1,2,1) 100%)',
          }}
        />
      </div>

      {/* Header with Logo */}
      <header className="absolute left-0 right-0 top-0 flex h-20 items-center justify-between px-[42px] py-[21px]">
        <div className="flex items-center gap-9">
          <Link to="/" className="flex items-center">
            <img src={logoIcon} alt="모두의 플리 로고" className="size-9" />
          </Link>
        </div>
      </header>

      {/* Main Content */}
      <div className="relative z-10 flex w-full flex-col items-center px-4">
        {step === 1 && (
          <ResetPasswordForm
            onSuccess={(submittedEmail) => {
              setEmail(submittedEmail);
              setStep(2);
            }}
          />
        )}

        {step === 2 && (
          <VerifyTempPasswordForm email={email} onSuccess={() => setStep(3)} />
        )}

        {step === 3 && <ChangePasswordForm />}
      </div>
    </div>
  );
}
