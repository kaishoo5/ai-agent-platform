import { Link } from "react-router-dom";

function NotFoundPage() {
    return (
        <main className="not-found-page">
            <h1>404</h1>
            <p>요청한 페이지를 찾을 수 없습니다.</p>

            <Link to="/">
                채팅 화면으로 돌아가기
            </Link>
        </main>
    );
}

export default NotFoundPage;