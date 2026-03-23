package com.example.dopaminecut2.logic

import com.example.dopaminecut2.model.User
import com.google.firebase.firestore.FirebaseFirestore

class UserRepository {
    /** Firestore 데이터베이스 인스턴스 접근용 객체 */
    private val db = FirebaseFirestore.getInstance()

    /** 유저 데이터가 저장되는 최상위 컬렉션("Users") 참조 */
    private val usersCollection = db.collection("Users")

    /**
     * 회원가입 시 새로운 유저 데이터를 DB에 생성(저장)함
     * - 전달받는 User 객체 내부에 커뮤니티 정지 여부(isBanned) 등 기본 설정값이 포함됨
     * @param user DB에 저장할 사용자 데이터 객체 (uid를 문서 ID로 사용)
     * @param onComplete 저장 성공/실패 여부를 반환하는 콜백 함수
     */
    fun createNewUser(user: User, onComplete: (Boolean) -> Unit) {
        usersCollection.document(user.uid).set(user)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    /**
     * 특정 유저의 상세 정보를 DB에서 불러옴
     * - 커뮤니티 진입 시 이 함수를 호출하여 해당 유저의 정지 상태(isBanned)를 확인하고,
     * 정지된 유저라면 글쓰기/댓글/추천 버튼을 숨기거나 막는 용도로 활용함
     * @param uid 조회할 사용자의 고유 식별자
     * @param onComplete 조회된 User 객체를 반환 (실패 시 null)하는 콜백 함수
     */
    fun getUserInfo(uid: String, onComplete: (User?) -> Unit) {
        usersCollection.document(uid).get()
            .addOnSuccessListener { document ->
                // Firestore 문서를 읽어와 User 데이터 클래스(isBanned 필드 포함)로 자동 변환
                val user = document.toObject(User::class.java)
                onComplete(user)
            }
            .addOnFailureListener { onComplete(null) }
    }
}