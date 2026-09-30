import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuthStore } from '@/lib/stores/useAuthStore';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { maskEmail } from '@/lib/utils/email';
import { useCountdown } from '@/lib/hooks/useCountdown';
import icMailbox from '@/assets/ic_mailbox.svg';
import icTimer from '@/assets/ic_timer.svg';

interface VerifyTempPasswordFormData {
  tempPassword: string;
}

interface VerifyTempPasswordFormProps {
  email: string;
  onSuccess: () => void;
}

export default function VerifyTempPasswordForm({
  email,
  onSuccess,
}: VerifyTempPasswordFormProps) {
  const [isLoading, setIsLoading] = useState(false);
  const { signIn } = useAuthStore();
  const { formatTime, isExpired } = useCountdown(180); // 3 minutes = 180 seconds

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<VerifyTempPasswordFormData>();

  // Show toast when timer expires
  useEffect(() => {
    if (isExpired) {
      toast.error('인증 시간이 만료되었습니다. 다시 시도해주세요.');
    }
  }, [isExpired]);

  const onSubmit = async (data: VerifyTempPasswordFormData) => {
    if (isExpired) {
      toast.error('인증 시간이 만료되었습니다. 다시 시도해주세요.');
      return;
    }

    setIsLoading(true);

    try {
      // Use signIn to verify temporary password
      await signIn(email, data.tempPassword);

      // Keep logged in - Step 3 requires authentication to change password
      toast.success('임시 비밀번호 확인 완료');

      // Move to step 3 (password change)
      onSuccess();
    } catch (error) {
      console.error('Temporary password not matched.', error);

      toast.error('임시 비밀번호가 일치하지 않습니다. 다시 시도해주세요.');
    } finally {
      setIsLoading(false);
    }
  };

  const maskedEmail = maskEmail(email);

  return (
    <div className="flex w-full max-w-[500px] flex-col items-center gap-10">
      {/* Icon, Title, and Timer */}
      <div className="flex flex-col items-center gap-5">
        <img src={icMailbox} alt="" className="size-[70px]" />

        <div className="flex flex-col items-center text-center">
          <h1 className="text-header1-b-160 text-gray-200">{maskedEmail}으로</h1>
          <h1 className="text-header1-b-160 text-gray-200">
            전달 받은 임시 비밀번호를 입력해주세요
          </h1>
        </div>

        {/* Timer */}
        <div className="flex items-center gap-1">
          <img src={icTimer} alt="" className="size-[22px]" />
          <p className="text-center text-title1-sb text-pink-400">{formatTime()}</p>
        </div>
      </div>

      {/* Form */}
      <form onSubmit={handleSubmit(onSubmit)} className="flex w-full flex-col gap-[30px]">
        {/* Temp Password Input */}
        <div className="flex w-full flex-col gap-5">
          <div className="flex w-full flex-col gap-2.5">
            <Input
              id="tempPassword"
              type="password"
              placeholder="임시 비밀번호 입력"
              className={cn(
                'h-[54px] rounded-xl border-[1.5px]',
                errors.tempPassword ? 'border-[#c93c3f]' : 'border-gray-800',
                'bg-[rgba(35,35,43,0.5)] px-5 py-3.5 text-body2-m-140 text-white placeholder:text-gray-400',
              )}
              {...register('tempPassword', {
                required: '임시 비밀번호를 입력해주세요',
              })}
              disabled={isLoading || isExpired}
            />
            {errors.tempPassword && (
              <p className="px-2 text-body3-m text-red-notification">
                {errors.tempPassword.message}
              </p>
            )}
          </div>

          {/* Submit Button */}
          <div className="w-full pt-1.5">
            <Button
              type="submit"
              disabled={isLoading || isExpired}
              className="h-[54px] w-full rounded-xl bg-pink-600 text-body1-b text-white hover:bg-pink-500 disabled:bg-gray-800 disabled:text-gray-600"
            >
              {isLoading ? '확인 중...' : '비밀번호 초기화'}
            </Button>
          </div>
        </div>

        {/* Back to Login Link */}
        <div className="flex w-full items-center justify-center gap-1">
          <Link
            to="/sign-in"
            className="text-body2-m text-gray-300 underline decoration-solid underline-offset-[from-font] hover:text-gray-400"
          >
            로그인으로 돌아가기
          </Link>
        </div>
      </form>
    </div>
  );
}
